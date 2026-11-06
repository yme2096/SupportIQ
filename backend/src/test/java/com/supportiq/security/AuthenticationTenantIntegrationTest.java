package com.supportiq.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Instant;
import java.net.URI;
import java.lang.reflect.Type;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthenticationTenantIntegrationTest.TestEmbeddings.class)
class AuthenticationTenantIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired com.supportiq.rag.EmbeddingProvider embeddings;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired com.supportiq.billing.BillingProvider billingProvider;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @LocalServerPort int port;

    @Test
    void expiredAccessTokenIsRejected() {
        Instant now = Instant.now();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder().issuer("supportiq").audience(List.of("supportiq-api")).subject(UUID.randomUUID().toString())
                        .issuedAt(now.minusSeconds(120)).expiresAt(now.minusSeconds(60)).claim("role", "COMPANY_ADMIN").claim("companyId", UUID.randomUUID().toString()).build())).getTokenValue();
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/users", expired, Map.class).getStatusCode());
    }

    @Test
    void publicWidgetsResolveAndKeepCompanyChatsIsolated() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Account a = register("Widget Alpha " + suffix, "widget-a-" + suffix + "@example.test");
        Account b = register("Widget Beta " + suffix, "widget-b-" + suffix + "@example.test");
        Map<String,Object> aProfile = get("/api/company/profile", a.accessToken, Map.class).getBody();
        Map<String,Object> bProfile = get("/api/company/profile", b.accessToken, Map.class).getBody();
        String publicA = Objects.toString(aProfile.get("public_id"));
        String publicB = Objects.toString(bProfile.get("public_id"));
        assertNotEquals(publicA, publicB);

        put("/api/company/settings", b.accessToken, Map.of("workspaceName", "Beta", "widget", Map.of(
                "name", "Beta help", "logoUrl", "https://example.test/beta.png", "welcomeMessage", "Welcome to Beta support.", "primaryColor", "#1267ab")));
        ResponseEntity<Map> configA = http.getForEntity("/api/public/widget/" + publicA + "/config", Map.class);
        ResponseEntity<Map> configB = http.getForEntity("/api/public/widget/" + publicB + "/config", Map.class);
        assertEquals("Widget Alpha " + suffix, configA.getBody().get("companyName"));
        assertEquals("Beta help", configB.getBody().get("widgetName"));
        assertEquals("#1267ab", configB.getBody().get("primaryColor"));
        assertEquals("Welcome to Beta support.", configB.getBody().get("welcomeMessage"));
        assertNotEquals(configA.getBody().get("publicId"), configB.getBody().get("publicId"));
        HttpHeaders originHeaders = new HttpHeaders(); originHeaders.setOrigin("https://merchant.example");
        ResponseEntity<Map> crossOriginConfig = http.exchange("/api/public/widget/" + publicB + "/config", HttpMethod.GET, new HttpEntity<>(originHeaders), Map.class);
        assertEquals("https://merchant.example", crossOriginConfig.getHeaders().getAccessControlAllowOrigin());

        // Even a forged slug in the request body cannot redirect a widget conversation to another tenant.
        HttpHeaders betaHeaders = new HttpHeaders(); betaHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> betaChat = http.exchange("/api/public/widget/" + publicB + "/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("message", "I need to speak to a human agent.", "companySlug", a.user.get("companySlug")), betaHeaders), Map.class);
        assertEquals(HttpStatus.OK, betaChat.getStatusCode());
        String betaConversation = Objects.toString(betaChat.getBody().get("conversationId"));
        String betaToken = Objects.toString(betaChat.getBody().get("chatAccessToken"));
        UUID betaCompanyId = UUID.fromString(Objects.toString(bProfile.get("id")));
        assertEquals(betaCompanyId, db.queryForObject("select company_id from conversations where id=?::uuid", UUID.class, betaConversation));
        assertEquals(1, db.queryForObject("select count(*) from support_tickets where company_id=? and conversation_id=?::uuid", Integer.class, betaCompanyId, betaConversation));

        String betaMessages = "/api/public/widget/" + publicB + "/conversations/" + betaConversation + "/messages";
        String alphaMessages = "/api/public/widget/" + publicA + "/conversations/" + betaConversation + "/messages";
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity(betaMessages, Map.class).getStatusCode());
        HttpHeaders tokenHeader = new HttpHeaders(); tokenHeader.set("X-Conversation-Token", betaToken);
        assertEquals(HttpStatus.OK, http.exchange(betaMessages, HttpMethod.GET, new HttpEntity<>(tokenHeader), List.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.exchange(alphaMessages, HttpMethod.GET, new HttpEntity<>(tokenHeader), Map.class).getStatusCode());

        HttpHeaders alphaHeaders = new HttpHeaders(); alphaHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> alphaChat = http.exchange("/api/public/widget/" + publicA + "/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("message", "How can I contact support?", "conversationId", betaConversation), alphaHeaders), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, alphaChat.getStatusCode());
    }

    @Test
    void privateNotesAssignmentLifecycleAndFeedbackStayCompanyScoped() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        Account alpha=register("Workspace Alpha "+suffix,"workspace-a-"+suffix+"@example.test");
        Account beta=register("Workspace Beta "+suffix,"workspace-b-"+suffix+"@example.test");
        Map<String,Object> betaProfile=get("/api/company/profile",beta.accessToken,Map.class).getBody();
        String publicId=Objects.toString(betaProfile.get("public_id"));
        Map<String,Object> invitation=post("/api/invitations",beta.accessToken,Map.of("email","workspace-agent-"+suffix+"@example.test"),Map.class).getBody();
        String inviteToken=UriComponentsBuilder.fromUriString(Objects.toString(invitation.get("invitationUrl"))).build().getQueryParams().getFirst("token");
        Map<String,Object> accepted=post("/api/invitations/accept",null,Map.of("token",inviteToken,"fullName","Workspace Agent","password","AgentPassword123!"),Map.class).getBody();
        String agentAccess=Objects.toString(accepted.get("accessToken"));
        String agentId=Objects.toString(((Map<String,Object>)accepted.get("user")).get("id"));
        ResponseEntity<Map> chat=http.postForEntity("/api/public/widget/"+publicId+"/chat",Map.of("message","Please connect me with a human agent."),Map.class);
        assertEquals(HttpStatus.OK,chat.getStatusCode());
        String conversation=Objects.toString(chat.getBody().get("conversationId"));
        String chatToken=Objects.toString(chat.getBody().get("chatAccessToken"));
        UUID ticket=db.queryForObject("select id from support_tickets where company_id=?::uuid and conversation_id=?::uuid order by created_at desc limit 1",UUID.class,betaProfile.get("id"),conversation);

        assertEquals(HttpStatus.NOT_FOUND,get("/api/v1/agent/workspace/conversations/"+conversation+"/messages",alpha.accessToken,Map.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,http.postForEntity("/api/v1/agent/workspace/conversations/"+conversation+"/assign",Map.of("agentId",agentId),Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,post("/api/v1/agent/workspace/conversations/"+conversation+"/assign",beta.accessToken,Map.of("agentId",agentId),Map.class).getStatusCode());
        assertEquals(UUID.fromString(agentId),db.queryForObject("select assigned_agent_id from support_tickets where id=?",UUID.class,ticket));
        assertEquals(HttpStatus.OK,put("/api/v1/agent/workspace/availability",agentAccess,Map.of("availability","AVAILABLE","workloadLimit",10)).getStatusCode());
        assertEquals(HttpStatus.OK,post("/api/v1/agent/workspace/conversations/"+conversation+"/assign",beta.accessToken,Map.of("automatic",true),Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,post("/api/v1/agent/workspace/conversations/"+conversation+"/notes",agentAccess,Map.of("content","PRIVATE NOTE: internal account review."),Map.class).getStatusCode());

        HttpHeaders capability=new HttpHeaders();capability.set("X-Conversation-Token",chatToken);
        ResponseEntity<Map> attachment=uploadWidgetAttachment(publicId,conversation,chatToken,"support-context.txt","Customer provided attachment.");
        assertEquals(HttpStatus.OK,attachment.getStatusCode());
        String attachmentId=Objects.toString(attachment.getBody().get("id"));
        HttpHeaders alphaAttachmentHeaders=new HttpHeaders();alphaAttachmentHeaders.set("X-Conversation-Token",chatToken);
        assertEquals(HttpStatus.NOT_FOUND,http.exchange("/api/public/widget/"+Objects.toString(get("/api/company/profile",alpha.accessToken,Map.class).getBody().get("public_id"))+"/attachments/"+attachmentId,HttpMethod.GET,new HttpEntity<>(alphaAttachmentHeaders),byte[].class).getStatusCode());
        assertArrayEquals("Customer provided attachment.".getBytes(StandardCharsets.UTF_8),http.exchange("/api/public/widget/"+publicId+"/attachments/"+attachmentId,HttpMethod.GET,new HttpEntity<>(capability),byte[].class).getBody());
        List<Map> publicMessages=http.exchange("/api/public/widget/"+publicId+"/conversations/"+conversation+"/messages",HttpMethod.GET,new HttpEntity<>(capability),List.class).getBody();
        assertFalse(publicMessages.stream().anyMatch(message->Objects.toString(message.get("content")).contains("PRIVATE NOTE")));
        List<Map> staffMessages=(List<Map>)get("/api/v1/agent/workspace/conversations/"+conversation+"/messages",agentAccess,List.class).getBody();
        assertTrue(staffMessages.stream().anyMatch(message->"INTERNAL".equals(message.get("visibility"))&&Objects.toString(message.get("author")).equals("Workspace Agent")));

        assertEquals(HttpStatus.OK,patch("/api/v1/agent/workspace/conversations/"+conversation+"/ticket",agentAccess,Map.of("status","RESOLVED","priority","HIGH","resolutionReason","The support question has been answered." )).getStatusCode());
        ResponseEntity<Map> feedback=http.exchange("/api/public/widget/"+publicId+"/conversations/"+conversation+"/feedback",HttpMethod.POST,new HttpEntity<>(Map.of("rating",5),capability),Map.class);
        assertEquals(HttpStatus.OK,feedback.getStatusCode());
        assertEquals(5,feedback.getBody().get("rating"));
        assertEquals(HttpStatus.OK,patch("/api/v1/agent/workspace/conversations/"+conversation+"/ticket",agentAccess,Map.of("status","OPEN","resolutionReason","" )).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,http.exchange("/api/public/widget/"+publicId+"/conversations/"+conversation+"/feedback",HttpMethod.POST,new HttpEntity<>(Map.of("rating",4),capability),Map.class).getStatusCode());
    }

    @Test
    void websocketDeliversLiveRepliesDeduplicatesAndRejectsCrossTenantSubscriptions() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        Account alpha=register("Realtime Alpha "+suffix,"realtime-a-"+suffix+"@example.test");
        Account beta=register("Realtime Beta "+suffix,"realtime-b-"+suffix+"@example.test");
        Map<String,Object> betaProfile=get("/api/company/profile",beta.accessToken,Map.class).getBody();
        String publicId=Objects.toString(betaProfile.get("public_id"));
        Map<String,Object> invite=post("/api/invitations",beta.accessToken,Map.of("email","realtime-agent-"+suffix+"@example.test"),Map.class).getBody();
        String inviteToken=UriComponentsBuilder.fromUriString(Objects.toString(invite.get("invitationUrl"))).build().getQueryParams().getFirst("token");
        Map<String,Object> accepted=post("/api/invitations/accept",null,Map.of("token",inviteToken,"fullName","Realtime Agent","password","AgentPassword123!"),Map.class).getBody();
        String agentToken=Objects.toString(accepted.get("accessToken"));
        Map<String,Object> chat=http.postForEntity("/api/public/widget/"+publicId+"/chat",Map.of("message","I need to speak with a human agent."),Map.class).getBody();
        String conversation=Objects.toString(chat.get("conversationId"));

        WebSocketStompClient client=new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());client.start();
        StompHeaders customerHeaders=new StompHeaders();customerHeaders.add("X-Company-Public-Id",publicId);customerHeaders.add("X-Conversation-Id",conversation);customerHeaders.add("X-Conversation-Token",Objects.toString(chat.get("chatAccessToken")));
        StompHeaders agentHeaders=new StompHeaders();agentHeaders.add("Authorization","Bearer "+agentToken);
        StompSession customer=client.connectAsync(URI.create("ws://localhost:"+port+"/ws"),null,customerHeaders,new StompSessionHandlerAdapter(){}).get(10,TimeUnit.SECONDS);
        StompSession agent=client.connectAsync(URI.create("ws://localhost:"+port+"/ws"),null,agentHeaders,new StompSessionHandlerAdapter(){}).get(10,TimeUnit.SECONDS);
        CountDownLatch customerReceived=new CountDownLatch(1),agentReceived=new CountDownLatch(1),alphaReceived=new CountDownLatch(1);
        AtomicReference<String> customerText=new AtomicReference<>(),agentText=new AtomicReference<>();
        customer.subscribe("/topic/conversations/"+conversation,new StompFrameHandler(){public Type getPayloadType(StompHeaders h){return Map.class;}public void handleFrame(StompHeaders h,Object payload){if(payload instanceof Map<?,?> event){if("MESSAGE".equals(event.get("type"))&&event.get("message") instanceof Map<?,?> m){customerText.set(Objects.toString(m.get("content")));customerReceived.countDown();}}}});
        agent.subscribe("/topic/conversations/"+conversation,new StompFrameHandler(){public Type getPayloadType(StompHeaders h){return Map.class;}public void handleFrame(StompHeaders h,Object payload){if(payload instanceof Map<?,?> event&&"MESSAGE_SNAPSHOT".equals(event.get("type"))){agentText.set(Objects.toString(event.get("messages")));agentReceived.countDown();}}});
        StompSession alphaSession=client.connectAsync(URI.create("ws://localhost:"+port+"/ws"),null,bearer("Bearer "+alpha.accessToken),new StompSessionHandlerAdapter(){}).get(10,TimeUnit.SECONDS);
        alphaSession.subscribe("/topic/conversations/"+conversation,new StompFrameHandler(){public Type getPayloadType(StompHeaders h){return Map.class;}public void handleFrame(StompHeaders h,Object payload){alphaReceived.countDown();}});
        Thread.sleep(200);
        customer.send("/app/customer/conversations/"+conversation+"/send",Map.of("message","Live customer message "+suffix,"clientMessageId",UUID.randomUUID().toString()));
        assertTrue(agentReceived.await(5,TimeUnit.SECONDS),"The assigned company agent should receive the customer update live.");
        assertTrue(Objects.toString(agentText.get()).contains("Live customer message "+suffix));

        String messageId=UUID.randomUUID().toString();Map<String,Object> reply=Map.of("message","Live agent reply "+suffix,"clientMessageId",messageId);
        agent.send("/app/agent/conversations/"+conversation+"/reply",reply);
        assertTrue(customerReceived.await(5,TimeUnit.SECONDS),"The customer should receive the agent reply without polling.");
        assertEquals("Live agent reply "+suffix,customerText.get());
        agent.send("/app/agent/conversations/"+conversation+"/reply",reply);
        Thread.sleep(300);
        assertEquals(1,db.queryForObject("select count(*) from messages where company_id=?::uuid and conversation_id=?::uuid and client_message_id=?::uuid",Integer.class,betaProfile.get("id"),conversation,messageId));
        assertFalse(alphaReceived.await(400,TimeUnit.MILLISECONDS),"Company A must not receive Company B conversation events.");
        if(customer.isConnected())customer.disconnect();if(agent.isConnected())agent.disconnect();if(alphaSession.isConnected())alphaSession.disconnect();client.stop();
    }

    @Test
    void verifiedOrdersAndRefundsAreTenantScopedIdempotentAndNeverAssumeCredit() throws Exception {
        db.update("delete from order_lookup_limits");
        String suffix=UUID.randomUUID().toString().substring(0,8);
        Account alpha=register("Order Alpha "+suffix,"order-alpha-"+suffix+"@example.test");
        Account beta=register("Order Beta "+suffix,"order-beta-"+suffix+"@example.test");
        String alphaPublic=Objects.toString(get("/api/company/profile",alpha.accessToken,Map.class).getBody().get("public_id"));
        String betaPublic=Objects.toString(get("/api/company/profile",beta.accessToken,Map.class).getBody().get("public_id"));
        Map<String,Object> orderBody=Map.of("orderNumber","ORD-"+suffix,"customerName","Taylor Customer","customerEmail","taylor-"+suffix+"@example.test","postalCode","560001","totalAmount","100.00","currency","INR","paymentStatus","PAID","fulfillmentStatus","SHIPPED","items",List.of(Map.of("name","Headphones","quantity",1,"amount",100,"category","AUDIO")),"deliveryAddress",Map.of("city","Bengaluru","postalCode","560001"));
        ResponseEntity<Map> made=post("/api/v1/orders",alpha.accessToken,orderBody,Map.class);assertEquals(HttpStatus.OK,made.getStatusCode());
        UUID orderId=UUID.fromString(Objects.toString(made.getBody().get("id")));
        assertEquals(HttpStatus.NOT_FOUND,get("/api/v1/orders/"+orderId,beta.accessToken,Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,get("/api/v1/orders/"+orderId,alpha.accessToken,Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,patch("/api/v1/orders/"+orderId,alpha.accessToken,Map.of("fulfillmentStatus","DELIVERED")).getStatusCode());
        Map<String,Object> verifyBody=Map.of("publicCompanyId",alphaPublic,"orderNumber","ORD-"+suffix,"email","taylor-"+suffix+"@example.test","postalCode","560001");
        ResponseEntity<Map> verified=http.postForEntity("/api/public/orders/verify",verifyBody,Map.class);assertEquals(HttpStatus.OK,verified.getStatusCode());
        String access=Objects.toString(verified.getBody().get("accessToken"));assertNotNull(access);
        HttpHeaders orderHeaders=new HttpHeaders();orderHeaders.set("X-Order-Access-Token",access);
        assertEquals(HttpStatus.OK,http.exchange("/api/public/orders/current",HttpMethod.GET,new HttpEntity<>(orderHeaders),Map.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,http.exchange("/api/public/orders/verify",HttpMethod.POST,new HttpEntity<>(Map.of("publicCompanyId",betaPublic,"orderNumber","ORD-"+suffix,"email","taylor-"+suffix+"@example.test","postalCode","560001")),Map.class).getStatusCode());
        HttpHeaders guessedHeaders=new HttpHeaders();guessedHeaders.set("X-Order-Access-Token","guessable-order-id");
        assertEquals(HttpStatus.NOT_FOUND,http.exchange("/api/public/orders/current",HttpMethod.GET,new HttpEntity<>(guessedHeaders),Map.class).getStatusCode());

        Map<String,Object> refundBody=Map.of("amount","30.50","reason","The item arrived late.","condition","UNUSED","category","AUDIO");
        HttpHeaders refundHeaders=new HttpHeaders(orderHeaders);refundHeaders.setContentType(MediaType.APPLICATION_JSON);refundHeaders.set("Idempotency-Key","test-refund-"+suffix);
        ResponseEntity<Map> requested=http.exchange("/api/public/orders/current/refunds",HttpMethod.POST,new HttpEntity<>(refundBody,refundHeaders),Map.class);assertEquals(HttpStatus.OK,requested.getStatusCode());
        String refundId=Objects.toString(requested.getBody().get("id"));assertEquals("UNDER_REVIEW",requested.getBody().get("status"));
        ResponseEntity<Map> duplicate=http.exchange("/api/public/orders/current/refunds",HttpMethod.POST,new HttpEntity<>(refundBody,refundHeaders),Map.class);assertEquals(refundId,Objects.toString(duplicate.getBody().get("id")));
        assertEquals(HttpStatus.CONFLICT,post("/api/v1/refunds/"+refundId+"/approve",alpha.accessToken,Map.of("approvedAmount","101.00"),Map.class).getStatusCode());

        Map<String,Object> invite=post("/api/invitations",alpha.accessToken,Map.of("email","order-agent-"+suffix+"@example.test"),Map.class).getBody();
        String inviteToken=UriComponentsBuilder.fromUriString(Objects.toString(invite.get("invitationUrl"))).build().getQueryParams().getFirst("token");
        Map<String,Object> accepted=post("/api/invitations/accept",null,Map.of("token",inviteToken,"fullName","Order Agent","password","AgentPassword123!"),Map.class).getBody();
        String agentToken=Objects.toString(accepted.get("accessToken"));
        assertEquals(HttpStatus.FORBIDDEN,post("/api/v1/refunds/"+refundId+"/approve",agentToken,Map.of(),Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,post("/api/v1/refunds/"+refundId+"/review",agentToken,Map.of("note","Checked order condition"),Map.class).getStatusCode());
        ResponseEntity<Map> approved=post("/api/v1/refunds/"+refundId+"/approve",alpha.accessToken,Map.of("approvedAmount","30.50","note","Approved after review"),Map.class);assertEquals(HttpStatus.OK,approved.getStatusCode());
        ResponseEntity<Map> initiated=post("/api/v1/refunds/"+refundId+"/initiate",alpha.accessToken,Map.of(),Map.class);assertEquals("INITIATED",initiated.getBody().get("status"));assertNull(initiated.getBody().get("arn"));
        Map<String,Object> orderAfterInit=get("/api/v1/orders/"+orderId,alpha.accessToken,Map.class).getBody();assertEquals("PAID",orderAfterInit.get("payment_status"));
        assertEquals(HttpStatus.CONFLICT,post("/api/v1/refunds/"+refundId+"/initiate",alpha.accessToken,Map.of(),Map.class).getStatusCode());

        String webhook="{\"eventId\":\"event-"+suffix+"\",\"refundId\":\""+refundId+"\",\"status\":\"CREDITED\",\"providerReference\":\"demo-provider-"+suffix+"\"}";
        HttpHeaders invalid=new HttpHeaders();invalid.set("X-Provider-Signature","00");invalid.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.UNAUTHORIZED,http.exchange("/api/payments/webhooks/demo",HttpMethod.POST,new HttpEntity<>(webhook,invalid),Map.class).getStatusCode());
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("local-demo-webhook-secret-change-before-deploy".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        HttpHeaders valid=new HttpHeaders();valid.set("X-Provider-Signature",HexFormat.of().formatHex(mac.doFinal(webhook.getBytes(StandardCharsets.UTF_8))));valid.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.OK,http.exchange("/api/payments/webhooks/demo",HttpMethod.POST,new HttpEntity<>(webhook,valid),Map.class).getStatusCode());
        ResponseEntity<Map> duplicateEvent=http.exchange("/api/payments/webhooks/demo",HttpMethod.POST,new HttpEntity<>(webhook,valid),Map.class);assertEquals(Boolean.TRUE,duplicateEvent.getBody().get("duplicate"));
        assertEquals("CREDITED",get("/api/v1/refunds/"+refundId,alpha.accessToken,Map.class).getBody().get("status"));
        assertNull(get("/api/v1/refunds/"+refundId,alpha.accessToken,Map.class).getBody().get("arn"));
        assertEquals("PARTIALLY_REFUNDED",get("/api/v1/orders/"+orderId,alpha.accessToken,Map.class).getBody().get("payment_status"));

        HttpHeaders fullHeaders=new HttpHeaders(orderHeaders);fullHeaders.setContentType(MediaType.APPLICATION_JSON);fullHeaders.set("Idempotency-Key","full-refund-"+suffix);
        ResponseEntity<Map> fullRequest=http.exchange("/api/public/orders/current/refunds",HttpMethod.POST,new HttpEntity<>(Map.of("amount","69.50","reason","Return the remaining order value.","condition","UNUSED","category","AUDIO"),fullHeaders),Map.class);
        assertEquals("UNDER_REVIEW",fullRequest.getBody().get("status"));String fullRefundId=Objects.toString(fullRequest.getBody().get("id"));
        assertEquals(HttpStatus.OK,post("/api/v1/refunds/"+fullRefundId+"/approve",alpha.accessToken,Map.of("approvedAmount","69.50"),Map.class).getStatusCode());
        assertEquals("INITIATED",post("/api/v1/refunds/"+fullRefundId+"/initiate",alpha.accessToken,Map.of(),Map.class).getBody().get("status"));
        String fullWebhook="{\"eventId\":\"full-event-"+suffix+"\",\"refundId\":\""+fullRefundId+"\",\"status\":\"CREDITED\"}";
        mac.init(new javax.crypto.spec.SecretKeySpec("local-demo-webhook-secret-change-before-deploy".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        HttpHeaders fullSignature=new HttpHeaders();fullSignature.set("X-Provider-Signature",HexFormat.of().formatHex(mac.doFinal(fullWebhook.getBytes(StandardCharsets.UTF_8))));fullSignature.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.OK,http.exchange("/api/payments/webhooks/demo",HttpMethod.POST,new HttpEntity<>(fullWebhook,fullSignature),Map.class).getStatusCode());
        assertEquals("REFUNDED",get("/api/v1/orders/"+orderId,alpha.accessToken,Map.class).getBody().get("payment_status"));

        Map<String,Object> failedOrder=post("/api/v1/orders",alpha.accessToken,Map.of("orderNumber","FAILED-"+suffix,"customerName","Morgan Customer","customerEmail","morgan-"+suffix+"@example.test","postalCode","560003","totalAmount","15.00","currency","INR","paymentStatus","PAID","fulfillmentStatus","SHIPPED","items",List.of(Map.of("name","Cable","quantity",1,"amount",15)),"deliveryAddress",Map.of("postalCode","560003")),Map.class).getBody();
        UUID failedOrderId=UUID.fromString(Objects.toString(failedOrder.get("id")));patch("/api/v1/orders/"+failedOrderId,alpha.accessToken,Map.of("fulfillmentStatus","DELIVERED"));
        Map<String,Object> failedVerification=http.postForEntity("/api/public/orders/verify",Map.of("publicCompanyId",alphaPublic,"orderNumber","FAILED-"+suffix,"email","morgan-"+suffix+"@example.test","postalCode","560003"),Map.class).getBody();
        HttpHeaders failedHeaders=new HttpHeaders();failedHeaders.set("X-Order-Access-Token",Objects.toString(failedVerification.get("accessToken")));failedHeaders.setContentType(MediaType.APPLICATION_JSON);failedHeaders.set("Idempotency-Key","failed-"+suffix);
        String failedRefundId=Objects.toString(http.exchange("/api/public/orders/current/refunds",HttpMethod.POST,new HttpEntity<>(Map.of("amount","15.00","reason","Item did not work.","condition","UNUSED"),failedHeaders),Map.class).getBody().get("id"));
        assertEquals(HttpStatus.OK,post("/api/v1/refunds/"+failedRefundId+"/approve",alpha.accessToken,Map.of(),Map.class).getStatusCode());post("/api/v1/refunds/"+failedRefundId+"/initiate",alpha.accessToken,Map.of(),Map.class);
        String failedWebhook="{\"eventId\":\"failed-event-"+suffix+"\",\"refundId\":\""+failedRefundId+"\",\"status\":\"FAILED\"}";
        mac.init(new javax.crypto.spec.SecretKeySpec("local-demo-webhook-secret-change-before-deploy".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        HttpHeaders failedSignature=new HttpHeaders();failedSignature.set("X-Provider-Signature",HexFormat.of().formatHex(mac.doFinal(failedWebhook.getBytes(StandardCharsets.UTF_8))));failedSignature.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.OK,http.exchange("/api/payments/webhooks/demo",HttpMethod.POST,new HttpEntity<>(failedWebhook,failedSignature),Map.class).getStatusCode());
        assertEquals("FAILED",get("/api/v1/refunds/"+failedRefundId,alpha.accessToken,Map.class).getBody().get("status"));
        assertEquals("PAID",get("/api/v1/orders/"+failedOrderId,alpha.accessToken,Map.class).getBody().get("payment_status"));

        Map<String,Object> unpaidOrder=post("/api/v1/orders",alpha.accessToken,Map.of("orderNumber","UNPAID-"+suffix,"customerName","Sam Customer","customerEmail","sam-"+suffix+"@example.test","postalCode","560002","totalAmount","25.00","currency","INR","paymentStatus","PENDING","fulfillmentStatus","SHIPPED","items",List.of(Map.of("name","Case","quantity",1,"amount",25)),"deliveryAddress",Map.of("postalCode","560002")),Map.class).getBody();
        UUID unpaidId=UUID.fromString(Objects.toString(unpaidOrder.get("id")));patch("/api/v1/orders/"+unpaidId,alpha.accessToken,Map.of("fulfillmentStatus","DELIVERED"));
        ResponseEntity<Map> unpaidVerified=http.postForEntity("/api/public/orders/verify",Map.of("publicCompanyId",alphaPublic,"orderNumber","UNPAID-"+suffix,"email","sam-"+suffix+"@example.test","postalCode","560002"),Map.class);
        HttpHeaders unpaidHeaders=new HttpHeaders();unpaidHeaders.set("X-Order-Access-Token",Objects.toString(unpaidVerified.getBody().get("accessToken")));unpaidHeaders.setContentType(MediaType.APPLICATION_JSON);unpaidHeaders.set("Idempotency-Key","ineligible-"+suffix);
        ResponseEntity<Map> ineligible=http.exchange("/api/public/orders/current/refunds",HttpMethod.POST,new HttpEntity<>(Map.of("amount","25.00","reason","The order was not paid.","condition","UNUSED"),unpaidHeaders),Map.class);
        assertEquals("REJECTED",ineligible.getBody().get("status"));
        Map<String,Object> unverifiedChat=http.postForEntity("/api/public/widget/"+alphaPublic+"/chat",Map.of("message","Where is my order ORD-"+suffix+" and can I refund it?"),Map.class).getBody();
        assertEquals(Boolean.TRUE,unverifiedChat.get("escalated"));
        assertEquals(2,db.queryForObject("select count(*) from refunds where order_id=?::uuid",Integer.class,orderId));
    }

    private StompHeaders bearer(String value){StompHeaders headers=new StompHeaders();headers.add("Authorization",value);return headers;}

    @Test
    void registrationAuthenticationInvitationsAndTenantBoundaries() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Account a = register("Acme " + suffix, "admin-a-" + suffix + "@example.test");
        Account b = register("Beacon " + suffix, "admin-b-" + suffix + "@example.test");
        assertEquals("COMPANY_ADMIN", a.user.get("role"));
        assertNotNull(a.user.get("companySlug"));

        ResponseEntity<Map> duplicate = registerRaw("Duplicate " + suffix, (String) a.user.get("email"), "Password12345!");
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        ResponseEntity<Map> malformed = http.postForEntity("/api/auth/register-company", Map.of("companyName", "", "businessEmail", "bad", "fullName", "X", "password", "short", "confirmPassword", "short"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/api/users", Map.class).getStatusCode());

        ResponseEntity<Map> badLogin = http.postForEntity("/api/auth/login", Map.of("email", a.user.get("email"), "password", "not-the-password"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, badLogin.getStatusCode());
        ResponseEntity<Map> goodLogin = http.postForEntity("/api/auth/login", Map.of("email", a.user.get("email"), "password", "Password12345!"), Map.class);
        assertEquals(HttpStatus.OK, goodLogin.getStatusCode());

        Map<String,Object> bProfile = get("/api/company/profile", b.accessToken, Map.class).getBody();
        String bCompanyId = Objects.toString(bProfile.get("id"));
        ResponseEntity<Map> upload = uploadKnowledge(b.accessToken, "beacon-policy-" + suffix + ".md", "Beacon-only policy: premium exchanges are allowed within 21 days.");
        assertEquals(HttpStatus.ACCEPTED, upload.getStatusCode());
        List<Map> bDocs = (List<Map>) get("/api/v1/knowledge", b.accessToken, List.class).getBody();
        List<Map> aDocs = (List<Map>) get("/api/v1/knowledge", a.accessToken, List.class).getBody();
        assertTrue(bDocs.stream().anyMatch(d -> Objects.toString(d.get("file_name")).contains("beacon-policy-")));
        assertFalse(aDocs.stream().anyMatch(d -> Objects.toString(d.get("file_name")).contains("beacon-policy-")));
        awaitReady(b.accessToken, "beacon-policy-" + suffix);
        // A perfect-similarity vector from another embedding space must never enter this query result.
        UUID foreignSpaceDocument=db.queryForObject("insert into knowledge_documents(company_id,file_name,content,status) values(?::uuid,?,?, 'READY') returning id",
                UUID.class,bCompanyId,"foreign-space-policy-"+suffix+".md","FOREIGN SPACE OVERRIDE: exchanges allowed for 999 days.");
        float[] questionVector=embeddings.embedQuery("Are premium exchanges allowed within 21 days?");
        db.update("insert into knowledge_chunks(company_id,document_id,chunk_index,chunk_text,embedding,embedding_provider,embedding_model) values(?::uuid,?,0,?,cast(? as vector),'OTHER','incompatible-model')",
                bCompanyId,foreignSpaceDocument,"FOREIGN SPACE OVERRIDE: exchanges allowed for 999 days.",vectorLiteral(questionVector));
        Map<String,Object> grounded = post("/api/v1/chat/message", b.accessToken,
                Map.of("message", "Are premium exchanges allowed within 21 days?"), Map.class).getBody();
        assertTrue(((List<Map>) grounded.get("sourceReferences")).stream().anyMatch(ref -> Objects.toString(ref.get("documentName")).contains("beacon-policy-")));
        assertFalse(((List<Map>) grounded.get("sourceReferences")).stream().anyMatch(ref -> Objects.toString(ref.get("documentName")).contains("foreign-space-policy-")));
        Map<String,Object> isolated = post("/api/v1/chat/message", a.accessToken,
                Map.of("message", "Are premium exchanges allowed within 21 days?"), Map.class).getBody();
        assertFalse(((List<Map>) isolated.get("sourceReferences")).stream().anyMatch(ref -> Objects.toString(ref.get("documentName")).contains("beacon-policy-")));

        Map<String,Object> bTicketResponse = post("/api/v1/chat/message", b.accessToken,
                Map.of("companySlug", b.user.get("companySlug"), "message", "Please connect me with a human agent."), Map.class).getBody();
        String bConversation = Objects.toString(bTicketResponse.get("conversationId"));
        List<Map> bTickets = (List<Map>) get("/api/v1/agent/tickets", b.accessToken, List.class).getBody();
        String bTicketId = Objects.toString(bTickets.get(0).get("id"));
        List<Map> aTickets = (List<Map>) get("/api/v1/agent/tickets", a.accessToken, List.class).getBody();
        assertFalse(aTickets.stream().anyMatch(t -> bTicketId.equals(Objects.toString(t.get("id")))));
        assertEquals(HttpStatus.NOT_FOUND, get("/api/v1/chat/" + bConversation + "/messages", a.accessToken, Map.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, patch("/api/v1/agent/tickets/" + bTicketId, a.accessToken, Map.of("status", "RESOLVED")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/users/" + b.user.get("id"), a.accessToken, Map.class).getStatusCode());

        // A supplied tenant slug cannot override the validated admin's company claim.
        Map<String,Object> forgedTenantChat = post("/api/v1/chat/message", a.accessToken,
                Map.of("companySlug", b.user.get("companySlug"), "message", "Where is my refund? Order SS-10022"), Map.class).getBody();
        assertTrue(Boolean.TRUE.equals(forgedTenantChat.get("escalated")));
        assertFalse(Objects.toString(forgedTenantChat.get("answer")).contains("1599"));

        Map<String,Object> invitation = post("/api/invitations", b.accessToken, Map.of("email", "agent-" + suffix + "@example.test"), Map.class).getBody();
        String inviteToken = UriComponentsBuilder.fromUriString(Objects.toString(invitation.get("invitationUrl"))).build().getQueryParams().getFirst("token");
        assertEquals(HttpStatus.OK, get("/api/invitations/validate?token=" + inviteToken, null, Map.class).getStatusCode());
        Map<String,Object> accepted = post("/api/invitations/accept", null, Map.of("token", inviteToken, "fullName", "Beacon Agent", "password", "AgentPassword123!"), Map.class).getBody();
        String agentAccess = Objects.toString(accepted.get("accessToken"));
        assertEquals("AGENT", ((Map<String,Object>) accepted.get("user")).get("role"));
        List<Map> bUsers = (List<Map>) get("/api/users", b.accessToken, List.class).getBody();
        List<Map> aUsers = (List<Map>) get("/api/users", a.accessToken, List.class).getBody();
        assertTrue(bUsers.stream().anyMatch(user -> Objects.equals(user.get("email"), invitation.get("email"))));
        assertFalse(aUsers.stream().anyMatch(user -> Objects.equals(user.get("email"), invitation.get("email"))));
        assertEquals(HttpStatus.FORBIDDEN, get("/api/users", agentAccess, Map.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, post("/api/invitations", agentAccess, Map.of("email", "nope-" + suffix + "@example.test"), Map.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/invitations/validate?token=" + inviteToken, null, Map.class).getStatusCode());

        Map<String,Object> expiredInvite = post("/api/invitations", b.accessToken, Map.of("email", "expired-" + suffix + "@example.test"), Map.class).getBody();
        String expiredToken = UriComponentsBuilder.fromUriString(Objects.toString(expiredInvite.get("invitationUrl"))).build().getQueryParams().getFirst("token");
        db.update("update agent_invitations set expires_at=now()-interval '1 minute' where email=?", expiredInvite.get("email"));
        assertEquals(HttpStatus.GONE, get("/api/invitations/validate?token=" + expiredToken, null, Map.class).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, put("/api/users/" + a.user.get("id") + "/role", a.accessToken, Map.of("role", "SUPER_ADMIN")).getStatusCode());

        String agentId = Objects.toString(((Map<String,Object>) accepted.get("user")).get("id"));
        db.update("update app_users set role_id=(select id from roles where name='CUSTOMER') where id=?", UUID.fromString(agentId));
        ResponseEntity<Map> customerLogin = http.postForEntity("/api/auth/login", Map.of("email", invitation.get("email"), "password", "AgentPassword123!"), Map.class);
        String customerAccess = Objects.toString(customerLogin.getBody().get("accessToken"));
        assertEquals(HttpStatus.FORBIDDEN, get("/api/users", customerAccess, Map.class).getStatusCode());
        db.update("update app_users set status='SUSPENDED' where id=?", UUID.fromString(agentId));
        assertEquals(HttpStatus.FORBIDDEN, http.postForEntity("/api/auth/login", Map.of("email", invitation.get("email"), "password", "AgentPassword123!"), Map.class).getStatusCode());

        String refreshCookie = cookie(goodLogin.getHeaders());
        HttpHeaders cookieHeaders = new HttpHeaders(); cookieHeaders.add(HttpHeaders.COOKIE, refreshCookie);
        ResponseEntity<Map> rotated = http.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(cookieHeaders), Map.class);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        ResponseEntity<Map> replay = http.exchange("/api/auth/refresh", HttpMethod.POST, new HttpEntity<>(cookieHeaders), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, replay.getStatusCode());
        HttpHeaders logoutHeaders = new HttpHeaders(); logoutHeaders.add(HttpHeaders.COOKIE, cookie(rotated.getHeaders()));
        assertEquals(HttpStatus.NO_CONTENT, http.exchange("/api/auth/logout", HttpMethod.POST, new HttpEntity<>(logoutHeaders), String.class).getStatusCode());
    }

    @Test
    void subscriptionBillingUsageAnalyticsAndPlatformAdminAreEnforced() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        Account alpha=register("Billing Alpha "+suffix,"billing-alpha-"+suffix+"@example.test");
        Account beta=register("Billing Beta "+suffix,"billing-beta-"+suffix+"@example.test");
        List<Map> plans=(List<Map>)http.getForEntity("/api/public/billing/plans",List.class).getBody();
        assertEquals(Set.of("STARTER","GROWTH","BUSINESS","ENTERPRISE"),plans.stream().map(p->Objects.toString(p.get("code"))).collect(java.util.stream.Collectors.toSet()));
        Map<String,Object> starter=plans.stream().filter(p->"STARTER".equals(p.get("code"))).findFirst().orElseThrow();
        assertEquals(0,new java.math.BigDecimal("999.00").compareTo(new java.math.BigDecimal(starter.get("monthly_price").toString())));
        assertEquals("TRIALING",get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("status"));
        assertEquals("TRIALING",get("/api/v1/billing/subscription",beta.accessToken,Map.class).getBody().get("status"));
        assertEquals(HttpStatus.FORBIDDEN,get("/api/platform/dashboard",alpha.accessToken,Map.class).getStatusCode());

        UUID starterPlan=db.queryForObject("select id from subscription_plans where code='STARTER'",UUID.class);
        int oldAiLimit=db.queryForObject("select max_ai_conversations from subscription_plans where id=?",Integer.class,starterPlan);
        try {
            db.update("update subscription_plans set max_ai_conversations=1 where id=?",starterPlan);
            Map<String,Object> first=post("/api/v1/chat/message",alpha.accessToken,Map.of("message","What is the return policy?"),Map.class).getBody();
            assertNotNull(first.get("conversationId"));
            assertEquals(1,db.queryForObject("select count(*) from usage_records where company_id=(select company_id from company_subscriptions where company_id=(select company_id from app_users where id=?)) and metric='AI_CONVERSATION'",Integer.class,UUID.fromString(Objects.toString(alpha.user.get("id")))));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS,post("/api/v1/chat/message",alpha.accessToken,Map.of("message","Start a second AI chat."),Map.class).getStatusCode());
            Map<String,Object> betaUsage=get("/api/v1/billing/usage",beta.accessToken,Map.class).getBody();
            assertEquals(0,((Map<String,Number>)betaUsage.get("usage")).get("aiConversations").intValue());
        } finally { db.update("update subscription_plans set max_ai_conversations=? where id=?",oldAiLimit,starterPlan); }

        Map<String,Object> analytics=get("/api/v1/analytics/report?from=2020-01-01&to=2099-12-31&interval=MONTH",alpha.accessToken,Map.class).getBody();
        Map<String,Object> summary=(Map<String,Object>)analytics.get("summary");assertTrue(((Number)summary.get("new_conversations")).intValue()>=1);
        Map<String,Object> betaAnalytics=get("/api/v1/analytics/report?from=2020-01-01&to=2099-12-31",beta.accessToken,Map.class).getBody();
        assertEquals(0,((Number)((Map<String,Object>)betaAnalytics.get("summary")).get("total_conversations")).intValue());

        ResponseEntity<Map> inviteOne=post("/api/invitations",alpha.accessToken,Map.of("email","a1-"+suffix+"@example.test"),Map.class);
        ResponseEntity<Map> inviteTwo=post("/api/invitations",alpha.accessToken,Map.of("email","a2-"+suffix+"@example.test"),Map.class);
        assertEquals(HttpStatus.OK,inviteOne.getStatusCode());assertEquals(HttpStatus.OK,inviteTwo.getStatusCode());
        assertEquals(HttpStatus.PAYMENT_REQUIRED,post("/api/invitations",alpha.accessToken,Map.of("email","a3-"+suffix+"@example.test"),Map.class).getStatusCode());

        ResponseEntity<Map> checkout=post("/api/v1/billing/checkout",alpha.accessToken,Map.of("planCode","GROWTH","billingInterval","MONTHLY"),Map.class);
        assertEquals(HttpStatus.OK,checkout.getStatusCode());UUID paymentId=UUID.fromString(Objects.toString(checkout.getBody().get("paymentId")));
        assertEquals("SUCCEEDED",post("/api/v1/billing/payments/"+paymentId+"/demo-result",alpha.accessToken,Map.of("outcome","SUCCEEDED"),Map.class).getBody().get("payment") instanceof Map p?p.get("status"):null);
        Map<String,Object> alphaSub=get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody();assertEquals("ACTIVE",alphaSub.get("status"));assertEquals("GROWTH",alphaSub.get("plan_code"));
        assertEquals(1,((List<?>)get("/api/v1/billing/invoices",alpha.accessToken,List.class).getBody()).size());
        assertEquals(0,((List<?>)get("/api/v1/billing/payments",beta.accessToken,List.class).getBody()).size());

        ResponseEntity<Map> downgrade=post("/api/v1/billing/checkout",alpha.accessToken,Map.of("planCode","STARTER","billingInterval","MONTHLY"),Map.class);
        assertEquals(Boolean.TRUE,downgrade.getBody().get("scheduled"));
        assertEquals("STARTER",get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("pending_plan_code"));

        ResponseEntity<Map> renewal=post("/api/v1/billing/checkout",alpha.accessToken,Map.of("planCode","STARTER","billingInterval","MONTHLY"),Map.class);
        UUID renewalId=UUID.fromString(Objects.toString(renewal.getBody().get("paymentId")));
        String eventId="billing-test-"+suffix;com.supportiq.billing.BillingProvider.SignedEvent signed=billingProvider.demoEvent(renewalId,eventId,"PAYMENT_SUCCEEDED");
        HttpHeaders webhookHeaders=new HttpHeaders();webhookHeaders.setContentType(MediaType.APPLICATION_JSON);webhookHeaders.set("X-Billing-Signature","invalid");
        assertEquals(HttpStatus.UNAUTHORIZED,http.exchange("/api/public/billing/webhook/demo",HttpMethod.POST,new HttpEntity<>(signed.body(),webhookHeaders),Map.class).getStatusCode());
        webhookHeaders.set("X-Billing-Signature",signed.signature());
        assertEquals(HttpStatus.OK,http.exchange("/api/public/billing/webhook/demo",HttpMethod.POST,new HttpEntity<>(signed.body(),webhookHeaders),Map.class).getStatusCode());
        Map duplicate=http.exchange("/api/public/billing/webhook/demo",HttpMethod.POST,new HttpEntity<>(signed.body(),webhookHeaders),Map.class).getBody();assertEquals(Boolean.TRUE,duplicate.get("duplicate"));
        assertEquals("STARTER",get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("plan_code"));

        Map<String,Object> failCheckout=post("/api/v1/billing/checkout",alpha.accessToken,Map.of("planCode","GROWTH","billingInterval","MONTHLY"),Map.class).getBody();
        UUID failId=UUID.fromString(Objects.toString(failCheckout.get("paymentId")));
        com.supportiq.billing.BillingProvider.SignedEvent failure=billingProvider.demoEvent(failId,"billing-fail-"+suffix,"PAYMENT_FAILED");
        webhookHeaders.set("X-Billing-Signature",failure.signature());assertEquals(HttpStatus.OK,http.exchange("/api/public/billing/webhook/demo",HttpMethod.POST,new HttpEntity<>(failure.body(),webhookHeaders),Map.class).getStatusCode());
        assertEquals("PAST_DUE",get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("status"));
        Map<String,Object> recovered=post("/api/v1/billing/checkout",alpha.accessToken,Map.of("planCode","GROWTH","billingInterval","MONTHLY"),Map.class).getBody();
        UUID recoveredId=UUID.fromString(Objects.toString(recovered.get("paymentId")));post("/api/v1/billing/payments/"+recoveredId+"/demo-result",alpha.accessToken,Map.of("outcome","SUCCEEDED"),Map.class);
        post("/api/v1/billing/cancel",alpha.accessToken,Map.of("atPeriodEnd",true),Map.class);
        assertEquals(Boolean.TRUE,get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("cancel_at_period_end"));
        post("/api/v1/billing/reactivate",alpha.accessToken,Map.of(),Map.class);
        assertEquals(Boolean.FALSE,get("/api/v1/billing/subscription",alpha.accessToken,Map.class).getBody().get("cancel_at_period_end"));

        String adminEmail="platform-"+suffix+"@example.test",adminPassword="Platform-Admin-Password-"+suffix;
        Integer superRole=db.queryForObject("select id from roles where name='SUPER_ADMIN'",Integer.class);
        UUID platformId=db.queryForObject("insert into app_users(company_id,role_id,email,full_name,password_hash,status) values(null,?,?,?,?, 'ACTIVE') returning id",UUID.class,superRole,adminEmail,"Test Platform Admin",passwordEncoder.encode(adminPassword));
        ResponseEntity<Map> platformLogin=http.postForEntity("/api/auth/login",Map.of("email",adminEmail,"password",adminPassword),Map.class);assertEquals(HttpStatus.OK,platformLogin.getStatusCode());String platformToken=Objects.toString(platformLogin.getBody().get("accessToken"));
        assertEquals(HttpStatus.OK,get("/api/platform/dashboard",platformToken,Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,get("/api/platform/companies?search=Billing%20Alpha",platformToken,List.class).getStatusCode());
        Map<String,Object> company=(Map<String,Object>)get("/api/company/profile",alpha.accessToken,Map.class).getBody();UUID companyId=UUID.fromString(Objects.toString(company.get("id")));
        assertEquals(HttpStatus.OK,put("/api/platform/companies/"+companyId+"/status",platformToken,Map.of("status","SUSPENDED")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,get("/api/v1/orders",alpha.accessToken,Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,put("/api/platform/companies/"+companyId+"/status",platformToken,Map.of("status","ACTIVE")).getStatusCode());
        assertTrue(((List<?>)get("/api/platform/audit",platformToken,List.class).getBody()).size()>0);
        db.update("delete from refresh_tokens where user_id=?",platformId);db.update("delete from app_users where id=?",platformId);
    }

    private Account register(String company, String email) {
        ResponseEntity<Map> response = registerRaw(company, email, "Password12345!");
        assertEquals(HttpStatus.OK, response.getStatusCode(), Objects.toString(response.getBody()));
        Map<String,Object> body = response.getBody();
        return new Account(Objects.toString(body.get("accessToken")), (Map<String,Object>) body.get("user"));
    }

    private ResponseEntity<Map> registerRaw(String company, String email, String password) {
        return http.postForEntity("/api/auth/register-company", Map.of("companyName", company, "businessEmail", email, "fullName", "Company Admin",
                "password", password, "confirmPassword", password, "phone", "", "website", ""), Map.class);
    }

    private ResponseEntity<Map> uploadKnowledge(String token, String fileName, String text) {
        MultiValueMap<String,Object> parts = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders(); partHeaders.setContentType(MediaType.TEXT_PLAIN);
        partHeaders.setContentDispositionFormData("file", fileName);
        HttpEntity<ByteArrayResource> part = new HttpEntity<>(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return fileName; }
        }, partHeaders);
        parts.add("file", part);
        HttpHeaders headers = authHeaders(token); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/v1/knowledge", HttpMethod.POST, new HttpEntity<>(parts, headers), Map.class);
    }

    private ResponseEntity<Map> uploadWidgetAttachment(String publicId,String conversation,String token,String fileName,String text){
        MultiValueMap<String,Object> parts=new LinkedMultiValueMap<>();HttpHeaders partHeaders=new HttpHeaders();partHeaders.setContentType(MediaType.TEXT_PLAIN);partHeaders.setContentDispositionFormData("file",fileName);HttpEntity<ByteArrayResource> part=new HttpEntity<>(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)){@Override public String getFilename(){return fileName;}},partHeaders);parts.add("file",part);HttpHeaders headers=new HttpHeaders();headers.set("X-Conversation-Token",token);headers.setContentType(MediaType.MULTIPART_FORM_DATA);return http.exchange("/api/public/widget/"+publicId+"/conversations/"+conversation+"/attachments",HttpMethod.POST,new HttpEntity<>(parts,headers),Map.class);
    }

    private void awaitReady(String token, String namePart) {
        for (int attempt=0; attempt<40; attempt++) {
            List<Map> rows=(List<Map>)get("/api/v1/knowledge",token,List.class).getBody();
            Map document=rows.stream().filter(row -> Objects.toString(row.get("file_name")).contains(namePart)).findFirst().orElseThrow();
            if("READY".equals(document.get("status"))) return;
            if("FAILED".equals(document.get("status"))) fail("Document processing failed: " + document.get("processing_error"));
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); fail("Interrupted while waiting for document ingestion."); }
        }
        fail("Document ingestion did not finish before the timeout.");
    }

    @TestConfiguration
    static class TestEmbeddings {
        @Bean @org.springframework.context.annotation.Primary
        com.supportiq.rag.EmbeddingProvider deterministicTestEmbeddings() {
            return new com.supportiq.rag.EmbeddingProvider() {
                public boolean available(){return true;}
                public String providerName(){return "TEST";}
                public String modelName(){return "deterministic-test";}
                public List<float[]> embedDocuments(List<String> texts){return texts.stream().map(this::vector).toList();}
                public float[] embedQuery(String text){return vector(text);}
                private float[] vector(String text){
                    float[] result=new float[768];
                    java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("[a-zA-Z0-9]{3,}").matcher(text.toLowerCase(Locale.ROOT));
                    while(matcher.find()) result[Math.floorMod(matcher.group().hashCode(),result.length)]+=1;
                    double norm=0; for(float value:result) norm+=value*value;
                    norm=Math.sqrt(norm); if(norm>0) for(int i=0;i<result.length;i++) result[i]/=(float)norm;
                    return result;
                }
            };
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder value=new StringBuilder("[");
        for(int i=0;i<vector.length;i++){if(i>0)value.append(',');value.append(Float.toString(vector[i]));}
        return value.append(']').toString();
    }

    private <T> ResponseEntity<T> get(String path, String token, Class<T> type) { return http.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), type); }
    private <T> ResponseEntity<T> post(String path, String token, Object body, Class<T> type) { return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), type); }
    private <T> ResponseEntity<T> put(String path, String token, Object body) { return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders(token)), (Class<T>) Map.class); }
    private <T> ResponseEntity<T> patch(String path, String token, Object body) { return http.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(token)), (Class<T>) Map.class); }
    private HttpHeaders authHeaders(String token) { HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token); return headers; }
    private String cookie(HttpHeaders headers) {
        String setCookie = headers.getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        return setCookie.split(";", 2)[0];
    }

    private record Account(String accessToken, Map<String,Object> user) {}
}
