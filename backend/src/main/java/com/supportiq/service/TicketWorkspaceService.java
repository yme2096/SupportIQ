package com.supportiq.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.realtime.RealtimePublisher;
import com.supportiq.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class TicketWorkspaceService {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final RealtimePublisher publisher;
    private final ObjectMapper json=new ObjectMapper();
    private static final List<String> STATUSES=List.of("OPEN","ASSIGNED","IN_PROGRESS","WAITING_FOR_CUSTOMER","WAITING_FOR_AGENT","RESOLVED","CLOSED");
    private static final List<String> PRIORITIES=List.of("LOW","NORMAL","HIGH","URGENT");
    public TicketWorkspaceService(JdbcTemplate db,PlatformTransactionManager manager,RealtimePublisher publisher){this.db=db;this.tx=new TransactionTemplate(manager);this.publisher=publisher;}

    public Map<String,Object> conversations(String queue,String status,String priority,String search,int page,int size){
        UUID company=TenantContext.requireCompanyId(),user=TenantContext.userIdOrNull();
        String q=Objects.toString(queue,"ALL").toUpperCase(Locale.ROOT),st=Objects.toString(status,"").toUpperCase(Locale.ROOT),pr=Objects.toString(priority,"").toUpperCase(Locale.ROOT),term=Objects.toString(search,"").trim();
        if(!List.of("ALL","MINE","UNASSIGNED").contains(q))throw new IllegalArgumentException("Invalid conversation queue.");
        if(!st.isBlank()&&!STATUSES.contains(st))throw new IllegalArgumentException("Invalid ticket status.");
        if(!pr.isBlank()&&!PRIORITIES.contains(pr))throw new IllegalArgumentException("Invalid ticket priority.");
        int safePage=Math.max(0,page),safeSize=Math.max(1,Math.min(size,100)),offset=safePage*safeSize;
        String base=" from conversations c left join lateral (select * from support_tickets x where x.company_id=c.company_id and x.conversation_id=c.id order by x.created_at desc limit 1) t on true left join app_users au on au.id=t.assigned_agent_id and au.company_id=t.company_id left join lateral (select content,sender_type,created_at from messages m where m.company_id=c.company_id and m.conversation_id=c.id and m.visibility='CUSTOMER' order by created_at desc limit 1) lm on true where c.company_id=? and (?='ALL' or (?='MINE' and t.assigned_agent_id=?) or (?='UNASSIGNED' and (t.assigned_agent_id is null or t.id is null))) and (?='' or t.status=?) and (?='' or coalesce(t.priority,'NORMAL')=?) and (?='' or c.customer_name ilike '%'||?||'%' or coalesce(lm.content,'') ilike '%'||?||'%')";
        String paramsSearch="";
        Integer count=db.queryForObject("select count(*)"+base,Integer.class,company,q,q,user,q,st,st,pr,pr,term,term,term);
        List<Map<String,Object>> rows=db.queryForList("select c.id conversation_id,c.customer_name,c.status conversation_status,c.created_at,c.updated_at,t.id ticket_id,coalesce(t.title,'Customer conversation') title,t.reason,t.status ticket_status,coalesce(t.priority,'NORMAL') priority,t.assigned_agent_id,au.full_name assigned_agent,coalesce(lm.content,'') latest_message,lm.sender_type latest_sender,lm.created_at latest_at,(select count(*) from messages um where um.company_id=c.company_id and um.conversation_id=c.id and um.sender_type='CUSTOMER' and um.visibility='CUSTOMER' and (c.agent_last_read_at is null or um.created_at>c.agent_last_read_at)) unread_count"+base+" order by coalesce(lm.created_at,c.updated_at) desc limit ? offset ?",company,q,q,user,q,st,st,pr,pr,term,term,term,safeSize,offset);
        return Map.of("items",rows,"page",safePage,"size",safeSize,"total",count==null?0:count);
    }

    public List<Map<String,Object>> agents(){return db.queryForList("select u.id,u.full_name,u.email,u.status,coalesce(p.availability,'OFFLINE') availability,coalesce(p.online,false) online,coalesce(p.workload_limit,10) workload_limit,(select count(*) from support_tickets t where t.company_id=u.company_id and t.assigned_agent_id=u.id and t.status not in ('RESOLVED','CLOSED')) workload from app_users u join roles r on r.id=u.role_id left join agent_presence p on p.company_id=u.company_id and p.agent_id=u.id where u.company_id=? and r.name='AGENT' order by u.full_name",TenantContext.requireCompanyId());}

    public Map<String,Object> availability(String value,Integer limit){
        String state=Objects.toString(value,"OFFLINE").toUpperCase(Locale.ROOT);if(!List.of("AVAILABLE","BUSY","OFFLINE").contains(state))throw new IllegalArgumentException("Availability must be AVAILABLE, BUSY, or OFFLINE.");
        UUID company=TenantContext.requireCompanyId(),user=TenantContext.userIdOrNull(); if(!"AGENT".equals(TenantContext.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only agents can update their availability.");
        int workload=limit==null?10:limit;if(workload<1||workload>100)throw new IllegalArgumentException("Workload limit must be between 1 and 100.");
        db.update("insert into agent_presence(company_id,agent_id,availability,online,workload_limit,last_seen_at,updated_at) values(?,?,?,true,?,now(),now()) on conflict(company_id,agent_id) do update set availability=excluded.availability,online=true,workload_limit=excluded.workload_limit,last_seen_at=now(),updated_at=now()",company,user,state,workload);
        return Map.of("availability",state,"online",true,"workloadLimit",workload);
    }

    public Map<String,Object> assign(UUID conversationId,UUID target,boolean automatic){
        UUID company=TenantContext.requireCompanyId(),actor=TenantContext.userIdOrNull();
        return tx.execute(status->{
            List<Map<String,Object>> conversations=db.queryForList("select id,customer_name from conversations where id=? and company_id=? for update",conversationId,company);if(conversations.isEmpty())throw notFound();
            List<Map<String,Object>> tickets=db.queryForList("select id,status,assigned_agent_id from support_tickets where conversation_id=? and company_id=? and status not in ('RESOLVED','CLOSED') order by created_at desc limit 1 for update",conversationId,company);
            UUID ticketId;
            if(tickets.isEmpty()) ticketId=db.queryForObject("insert into support_tickets(company_id,conversation_id,title,reason,priority,status) values(?,?,?,'Agent requested conversation','NORMAL','OPEN') returning id",UUID.class,company,conversationId,"Support conversation");
            else ticketId=(UUID)tickets.getFirst().get("id");
            UUID agent=automatic?chooseAgent(company):target;
            if(agent!=null) validateAgent(company,agent);
            if(automatic&&agent!=null)db.update("update agent_presence set last_assigned_at=now(),updated_at=now() where company_id=? and agent_id=?",company,agent);
            UUID old=tickets.isEmpty()?null:(UUID)tickets.getFirst().get("assigned_agent_id");
            String oldStatus=tickets.isEmpty()?"OPEN":Objects.toString(tickets.getFirst().get("status"));
            String nextStatus=agent==null?"OPEN":"ASSIGNED";
            db.update("update support_tickets set assigned_agent_id=?,assigned_agent=(select full_name from app_users where id=? and company_id=?),status=?,updated_at=now() where id=? and company_id=?",agent,agent,company,nextStatus,ticketId,company);
            db.update("update conversations set assigned_agent_id=?,status='WAITING_FOR_AGENT',updated_at=now() where id=? and company_id=?",agent,conversationId,company);
            if(!Objects.equals(old,agent)) {
                String type=agent==null?"UNASSIGN":old==null?(automatic?"AUTO":"MANUAL"):"REASSIGN";
                db.update("insert into ticket_assignment_history(company_id,ticket_id,assigned_agent_id,actor_id,assignment_type) values(?,?,?,?,?)",company,ticketId,agent,actor,type);
                notify(company,agent,conversationId,ticketId,old==null?"A support conversation was assigned to you.":"A support conversation was reassigned to you.",agent==null?"TICKET_UNASSIGNED":"TICKET_ASSIGNED");
                notify(company,old,conversationId,ticketId,"A support conversation assignment changed.","TICKET_REASSIGNED");
            }
            if(!oldStatus.equals(nextStatus))db.update("insert into ticket_status_history(company_id,ticket_id,actor_id,old_status,new_status) values(?,?,?,?,?)",company,ticketId,actor,oldStatus,nextStatus);
            publishAfterCommit(()->publisher.conversation(conversationId,Map.of("type","ASSIGNMENT","agentId",agent==null?"":agent.toString(),"status",nextStatus)));
            return Map.of("ticketId",ticketId.toString(),"conversationId",conversationId.toString(),"assignedAgentId",agent==null?"":agent.toString(),"status",nextStatus);
        });
    }

    private UUID chooseAgent(UUID company){
        List<UUID> rows=db.query("select p.agent_id from agent_presence p join app_users u on u.id=p.agent_id and u.company_id=p.company_id join roles r on r.id=u.role_id where p.company_id=? and p.online=true and p.availability='AVAILABLE' and u.status='ACTIVE' and r.name='AGENT' and (select count(*) from support_tickets t where t.company_id=p.company_id and t.assigned_agent_id=p.agent_id and t.status not in ('RESOLVED','CLOSED'))<p.workload_limit order by "+assignmentOrder(company)+" for update of p skip locked limit 1",(rs,n)->rs.getObject(1,UUID.class),company);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void validateAgent(UUID company,UUID agent){if(db.queryForList("select u.id from app_users u join roles r on r.id=u.role_id where u.id=? and u.company_id=? and u.status='ACTIVE' and r.name='AGENT'",agent,company).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an active agent in this company.");}
    private String assignmentOrder(UUID company){String strategy=db.queryForObject("select coalesce(settings #>> '{support,assignmentStrategy}','ROUND_ROBIN') from company_settings where company_id=?",String.class,company);return "LEAST_LOADED".equalsIgnoreCase(strategy)?"(select count(*) from support_tickets t where t.company_id=p.company_id and t.assigned_agent_id=p.agent_id and t.status not in ('RESOLVED','CLOSED')),p.last_assigned_at nulls first,p.agent_id":"p.last_assigned_at nulls first,p.last_assigned_at,(select count(*) from support_tickets t where t.company_id=p.company_id and t.assigned_agent_id=p.agent_id and t.status not in ('RESOLVED','CLOSED')),p.agent_id";}

    public Map<String,Object> addNote(UUID conversationId,String content){
        UUID company=TenantContext.requireCompanyId(),actor=TenantContext.userIdOrNull(); String note=Objects.toString(content,"").trim();if(note.isBlank()||note.length()>4000)throw new IllegalArgumentException("Internal notes must be between 1 and 4000 characters.");
        if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,company).isEmpty())throw notFound();
        UUID id=db.queryForObject("insert into messages(company_id,conversation_id,sender_type,content,visibility,delivery_status) values(?,?, 'AGENT',?,'INTERNAL','READ') returning id",UUID.class,company,conversationId,note);
        db.update("update messages set author_id=? where id=? and company_id=?",actor,id,company);
        Map<String,Object> row=db.queryForMap("select m.id,m.sender_type,m.content,m.visibility,m.created_at,u.full_name author from messages m left join app_users u on u.id=m.author_id and u.company_id=m.company_id where m.id=? and m.company_id=?",id,company);
        publisher.staffConversation(conversationId,Map.of("type","INTERNAL_NOTE","message",row));
        return row;
    }
    public List<Map<String,Object>> messages(UUID conversationId){UUID c=TenantContext.requireCompanyId();if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,c).isEmpty())throw notFound();return db.queryForList("select m.id,m.sender_type,m.content,m.visibility,m.delivery_status,m.created_at,m.sources,m.source_refs,m.retrieval_score,u.full_name author,a.id attachment_id,a.file_name attachment_name,a.mime_type attachment_type,a.file_size attachment_size from messages m left join app_users u on u.id=m.author_id and u.company_id=m.company_id left join conversation_attachments a on a.message_id=m.id and a.company_id=m.company_id where m.company_id=? and m.conversation_id=? order by m.created_at",c,conversationId);}
    public Map<String,Object> history(UUID conversationId){UUID company=TenantContext.requireCompanyId();if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,company).isEmpty())throw notFound();List<UUID> ticket=db.query("select id from support_tickets where company_id=? and conversation_id=? order by created_at desc limit 1",(rs,n)->rs.getObject(1,UUID.class),company,conversationId);if(ticket.isEmpty())return Map.of("assignments",List.of(),"statuses",List.of());UUID id=ticket.getFirst();return Map.of("assignments",db.queryForList("select h.assignment_type,h.created_at,u.full_name agent,a.full_name actor from ticket_assignment_history h left join app_users u on u.id=h.assigned_agent_id and u.company_id=h.company_id left join app_users a on a.id=h.actor_id and a.company_id=h.company_id where h.company_id=? and h.ticket_id=? order by h.created_at desc",company,id),"statuses",db.queryForList("select h.old_status,h.new_status,h.reason,h.created_at,u.full_name actor from ticket_status_history h left join app_users u on u.id=h.actor_id and u.company_id=h.company_id where h.company_id=? and h.ticket_id=? order by h.created_at desc",company,id));}

    public Map<String,Object> lifecycle(UUID conversationId,String status,String priority,String resolution){
        UUID company=TenantContext.requireCompanyId(),actor=TenantContext.userIdOrNull();
        return tx.execute(txStatus->{
            List<Map<String,Object>> rows=db.queryForList("select t.id,t.status,t.priority,t.assigned_agent_id from support_tickets t join conversations c on c.id=t.conversation_id and c.company_id=t.company_id where c.id=? and t.company_id=? and t.status not in ('CLOSED') order by t.created_at desc limit 1 for update",conversationId,company);
            if(rows.isEmpty())throw notFound(); Map<String,Object> row=rows.getFirst();UUID ticket=(UUID)row.get("id");String old=Objects.toString(row.get("status"));String next=status==null?old:status.toUpperCase(Locale.ROOT),p=priority==null?Objects.toString(row.get("priority")):priority.toUpperCase(Locale.ROOT);
            if(!STATUSES.contains(next)||!PRIORITIES.contains(p))throw new IllegalArgumentException("Invalid ticket status or priority.");
            if(!transition(old,next))throw new ResponseStatusException(HttpStatus.CONFLICT,"That ticket status transition is not allowed.");
            String reason=Objects.toString(resolution,"").trim();if("RESOLVED".equals(next)&&(reason.isBlank()||reason.length()>600))throw new IllegalArgumentException("A resolution reason of at most 600 characters is required.");
            db.update("update support_tickets set status=?,priority=?,resolution_reason=case when ?='RESOLVED' then ? else resolution_reason end,resolved_at=case when ?='RESOLVED' then now() when ?='OPEN' then null else resolved_at end,closed_at=case when ?='CLOSED' then now() else closed_at end,updated_at=now() where id=? and company_id=?",next,p,next,reason,next,next,next,ticket,company);
            if(!old.equals(next))db.update("insert into ticket_status_history(company_id,ticket_id,actor_id,old_status,new_status,reason) values(?,?,?,?,?,?)",company,ticket,actor,old,next,reason.isBlank()?null:reason);
            db.update("update conversations set status=?,updated_at=now() where id=? and company_id=?",next.equals("RESOLVED")?"RESOLVED":next.equals("CLOSED")?"RESOLVED":"WAITING_FOR_AGENT",conversationId,company);
            if("RESOLVED".equals(next)||"CLOSED".equals(next))notify(company,(UUID)row.getOrDefault("assigned_agent_id",null),conversationId,ticket,"A support ticket was resolved.","TICKET_RESOLVED");
            publishAfterCommit(()->publisher.conversation(conversationId,Map.of("type","TICKET_STATUS","status",next,"priority",p)));
            return Map.of("id",ticket.toString(),"status",next,"priority",p,"resolutionReason",reason);
        });
    }
    private boolean transition(String from,String to){if(from.equals(to))return true;return switch(from){case "OPEN"->List.of("ASSIGNED","IN_PROGRESS","WAITING_FOR_AGENT","RESOLVED","CLOSED").contains(to);case "ASSIGNED"->List.of("OPEN","IN_PROGRESS","WAITING_FOR_CUSTOMER","WAITING_FOR_AGENT","RESOLVED","CLOSED").contains(to);case "IN_PROGRESS"->List.of("WAITING_FOR_CUSTOMER","WAITING_FOR_AGENT","RESOLVED","CLOSED","OPEN").contains(to);case "WAITING_FOR_CUSTOMER"->List.of("IN_PROGRESS","WAITING_FOR_AGENT","RESOLVED","CLOSED").contains(to);case "WAITING_FOR_AGENT"->List.of("ASSIGNED","IN_PROGRESS","RESOLVED","CLOSED").contains(to);case "RESOLVED"->"OPEN".equals(to)||"CLOSED".equals(to);default->false;};}

    public Map<String,Object> notifications(int page,int size){UUID c=TenantContext.requireCompanyId(),u=TenantContext.userIdOrNull();int limit=Math.max(1,Math.min(size,100)),offset=Math.max(page,0)*limit;List<Map<String,Object>> items=db.queryForList("select id,notification_type,conversation_id,ticket_id,summary,created_at,read_at from in_app_notifications where company_id=? and user_id=? order by created_at desc limit ? offset ?",c,u,limit,offset);Integer unread=db.queryForObject("select count(*) from in_app_notifications where company_id=? and user_id=? and read_at is null",Integer.class,c,u);return Map.of("items",items,"unreadCount",unread==null?0:unread);}
    public void readNotification(UUID id){UUID c=TenantContext.requireCompanyId(),u=TenantContext.userIdOrNull();db.update("update in_app_notifications set read_at=now() where id=? and company_id=? and user_id=? and read_at is null",id,c,u);}
    public Map<String,Object> feedback(UUID ticketId,int rating,String comment){UUID c=TenantContext.requireCompanyId();if(rating<1||rating>5)throw new IllegalArgumentException("Rating must be from 1 to 5.");String clean=Objects.toString(comment,"").trim();if(clean.length()>1000)throw new IllegalArgumentException("Feedback is limited to 1000 characters.");if(db.queryForList("select id from support_tickets where id=? and company_id=? and status in ('RESOLVED','CLOSED')",ticketId,c).isEmpty())throw notFound();UUID id=db.queryForObject("insert into customer_feedback(company_id,ticket_id,rating,comment) values(?,?,?,?) on conflict(ticket_id) do update set rating=excluded.rating,comment=excluded.comment,created_at=now() returning id",UUID.class,c,ticketId,rating,clean.isBlank()?null:clean);return Map.of("id",id.toString(),"rating",rating);}

    public Map<String,Object> sla(UUID conversationId){UUID c=TenantContext.requireCompanyId();Map<String,Object> row=db.queryForMap("select c.created_at,t.priority,t.status,(select min(created_at) from messages m where m.company_id=c.company_id and m.conversation_id=c.id and m.sender_type='AGENT' and m.visibility='CUSTOMER') first_response_at,(select max(created_at) from messages m where m.company_id=c.company_id and m.conversation_id=c.id and m.visibility='CUSTOMER') last_reply_at from conversations c left join lateral(select priority,status from support_tickets x where x.company_id=c.company_id and x.conversation_id=c.id order by created_at desc limit 1) t on true where c.id=? and c.company_id=?",conversationId,c);
        Map<String,Object> config=supportSettings();Instant created=instant(row.get("created_at")),now=Instant.now();String priority=Objects.toString(row.get("priority"),"NORMAL");int responseTarget=target(config,priority);int resolutionTarget=((Number)config.getOrDefault("resolutionTargetMinutes",1440)).intValue();Object first=row.get("first_response_at");long waiting=first==null?businessMinutes(created,now,config):0;Object last=row.get("last_reply_at");long since=businessMinutes(last==null?created:instant(last),now,config);return Map.of("firstResponseTargetMinutes",responseTarget,"resolutionTargetMinutes",resolutionTarget,"minutesWaitingForFirstResponse",waiting,"minutesSinceLastReply",since,"firstResponseOverdue",first==null&&waiting>responseTarget,"resolutionOverdue",!List.of("RESOLVED","CLOSED").contains(Objects.toString(row.get("status"),"OPEN"))&&businessMinutes(created,now,config)>resolutionTarget,"status",Objects.toString(row.get("status"),"OPEN"));}
    private Instant instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof java.time.OffsetDateTime t)return t.toInstant();if(value instanceof Instant t)return t;throw new IllegalStateException("Database timestamp is unavailable.");}

    public Map<String,Object> supportSettings(){UUID c=TenantContext.requireCompanyId();String raw=db.queryForObject("select settings::text from company_settings where company_id=?",String.class,c);try{Map<String,Object> all=json.readValue(raw,new TypeReference<>(){});Object support=all.get("support");return support instanceof Map<?,?> m?new LinkedHashMap<>((Map<String,Object>)m):Map.of();}catch(Exception ex){return Map.of();}}
    public Map<String,Object> updateSupportSettings(Map<String,Object> settings){
        int first=number(settings,"firstResponseTargetMinutes",1,10080),resolution=number(settings,"resolutionTargetMinutes",1,43200);
        String assignment=Objects.toString(settings.getOrDefault("assignmentStrategy","ROUND_ROBIN")).toUpperCase(Locale.ROOT);if(!List.of("ROUND_ROBIN","LEAST_LOADED").contains(assignment))throw new IllegalArgumentException("Assignment strategy must be ROUND_ROBIN or LEAST_LOADED.");
        Object hours=settings.get("businessHours");if(!(hours instanceof Map<?,?> h)||!Objects.toString(h.get("timezone"),"").matches("[A-Za-z_]+/[A-Za-z_]+"))throw new IllegalArgumentException("Set a valid business-hours timezone.");
        try{LocalTime start=LocalTime.parse(Objects.toString(h.get("start"))),end=LocalTime.parse(Objects.toString(h.get("end")));ZoneId.of(Objects.toString(h.get("timezone")));if(!end.isAfter(start))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalArgumentException("Business hours need a valid timezone and an end time after the start time.");}
        Object weekdays=h.get("weekdays");if(!(weekdays instanceof List<?> days)||days.isEmpty()||days.stream().anyMatch(day->!(day instanceof Number n)||n.intValue()<1||n.intValue()>7))throw new IllegalArgumentException("Choose at least one business day using ISO weekday numbers from 1 to 7.");
        Object targets=settings.get("priorityTargets");if(!(targets instanceof Map<?,?> targetMap))throw new IllegalArgumentException("Set a response target for each priority.");for(String key:PRIORITIES){Object target=targetMap.get(key);if(!(target instanceof Number n)||n.intValue()<1||n.intValue()>10080)throw new IllegalArgumentException("Priority response targets must be between 1 and 10080 minutes.");}
        Map<String,Object> value=new LinkedHashMap<>(settings);value.put("firstResponseTargetMinutes",first);value.put("resolutionTargetMinutes",resolution);value.put("assignmentStrategy",assignment);
        String encoded;try{encoded=json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}
        db.update("update company_settings set settings=jsonb_set(settings,'{support}',?::jsonb,true),updated_at=now() where company_id=?",encoded,TenantContext.requireCompanyId());return value;
    }
    private int number(Map<String,Object> s,String key,int min,int max){Object raw=s.get(key);if(!(raw instanceof Number n)||n.intValue()<min||n.intValue()>max)throw new IllegalArgumentException(key+" is out of range.");return n.intValue();}
    private int target(Map<String,Object> config,String priority){Object map=config.get("priorityTargets");if(map instanceof Map<?,?> values&&values.get(priority) instanceof Number n)return n.intValue();return ((Number)config.getOrDefault("firstResponseTargetMinutes",15)).intValue();}
    private long businessMinutes(Instant a,Instant b,Map<String,Object> c){if(!b.isAfter(a))return 0;Object raw=c.get("businessHours");if(!(raw instanceof Map<?,?> hours))return ChronoUnit.MINUTES.between(a,b);ZoneId zone=ZoneId.of(Objects.toString(hours.get("timezone"),"UTC"));LocalTime start=LocalTime.parse(Objects.toString(hours.get("start"),"09:00")),end=LocalTime.parse(Objects.toString(hours.get("end"),"18:00"));Set<Integer> weekdays=new HashSet<>();Object d=hours.get("weekdays");if(d instanceof List<?> days)for(Object value:days)if(value instanceof Number n)weekdays.add(n.intValue());if(weekdays.isEmpty())weekdays.addAll(List.of(1,2,3,4,5,6,7));ZonedDateTime from=a.atZone(zone),to=b.atZone(zone);long minutes=0;for(LocalDate day=from.toLocalDate();!day.isAfter(to.toLocalDate());day=day.plusDays(1)){if(!weekdays.contains(day.getDayOfWeek().getValue()))continue;Instant open=day.atTime(start).atZone(zone).toInstant(),close=day.atTime(end).atZone(zone).toInstant();Instant x=from.toInstant().isAfter(open)?from.toInstant():open,y=to.toInstant().isBefore(close)?to.toInstant():close;if(y.isAfter(x))minutes+=ChronoUnit.MINUTES.between(x,y);}return minutes;}

    public void notifyNewTicket(UUID company,UUID conversation,UUID ticket){List<UUID> users=db.query("select u.id from app_users u join roles r on r.id=u.role_id where u.company_id=? and u.status='ACTIVE' and r.name in ('AGENT','COMPANY_ADMIN')",(rs,n)->rs.getObject(1,UUID.class),company);for(UUID u:users)notify(company,u,conversation,ticket,"A new support conversation needs attention.","NEW_CONVERSATION");}
    public void onNewTicket(UUID company,UUID conversation,UUID ticket){tx.executeWithoutResult(txStatus->{
        db.update("insert into ticket_status_history(company_id,ticket_id,new_status,reason) values(?,?,'OPEN','Ticket created')",company,ticket);
        List<UUID> assigned=db.query("select p.agent_id from agent_presence p join app_users u on u.id=p.agent_id and u.company_id=p.company_id join roles r on r.id=u.role_id where p.company_id=? and p.online=true and p.availability='AVAILABLE' and u.status='ACTIVE' and r.name='AGENT' and (select count(*) from support_tickets t where t.company_id=p.company_id and t.assigned_agent_id=p.agent_id and t.status not in ('RESOLVED','CLOSED'))<p.workload_limit order by "+assignmentOrder(company)+" for update of p skip locked limit 1",(rs,n)->rs.getObject(1,UUID.class),company);
        if(!assigned.isEmpty()){
            UUID agent=assigned.getFirst();db.update("update agent_presence set last_assigned_at=now(),updated_at=now() where company_id=? and agent_id=?",company,agent);db.update("update support_tickets set assigned_agent_id=?,assigned_agent=(select full_name from app_users where id=? and company_id=?),status='ASSIGNED',updated_at=now() where id=? and company_id=?",agent,agent,company,ticket,company);
            db.update("update conversations set assigned_agent_id=?,status='WAITING_FOR_AGENT',updated_at=now() where id=? and company_id=?",agent,conversation,company);
            db.update("insert into ticket_assignment_history(company_id,ticket_id,assigned_agent_id,assignment_type) values(?,?,?,'AUTO')",company,ticket,agent);
            notify(company,agent,conversation,ticket,"A new support conversation was assigned to you.","TICKET_ASSIGNED");
        }else notifyNewTicket(company,conversation,ticket);
    });}
    public void notifyCustomerMessage(UUID conversation){UUID company=TenantContext.companyIdOrNull();Map<String,Object> assigned=db.query("select c.company_id,c.assigned_agent_id,t.id ticket_id from conversations c left join support_tickets t on t.company_id=c.company_id and t.conversation_id=c.id and t.status not in ('RESOLVED','CLOSED') where c.id=? order by t.created_at desc limit 1",rs->rs.next()?Map.of("company",rs.getObject("company_id",UUID.class),"agent",rs.getObject("assigned_agent_id",UUID.class)==null?"":rs.getObject("assigned_agent_id",UUID.class),"ticket",rs.getObject("ticket_id",UUID.class)==null?"":rs.getObject("ticket_id",UUID.class)):null,conversation);if(assigned==null)return;UUID tenant=(UUID)assigned.get("company");Object rawAgent=assigned.get("agent"),rawTicket=assigned.get("ticket");if(rawAgent instanceof UUID agent)notify(tenant,agent,conversation,rawTicket instanceof UUID t?t:null,"A customer sent a new message.","CUSTOMER_MESSAGE");}
    private void notify(UUID company,UUID user,UUID conversation,UUID ticket,String summary,String type){if(user==null)return;UUID id=db.queryForObject("insert into in_app_notifications(company_id,user_id,notification_type,conversation_id,ticket_id,summary) values(?,?,?,?,?,?) returning id",UUID.class,company,user,type,conversation,ticket,summary);publishAfterCommit(()->publisher.user(user,Map.of("id",id.toString(),"type",type,"conversationId",conversation.toString(),"summary",summary,"createdAt",Instant.now().toString())));}
    private void publishAfterCommit(Runnable callback){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){callback.run();}});else callback.run();}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation or ticket not found.");}
}
