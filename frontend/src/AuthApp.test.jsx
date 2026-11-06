import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom/vitest';
import AuthApp from './AuthApp.jsx';
import { api } from './api.js';

vi.mock('./api.js', () => ({ API_BASE: 'http://localhost:8080', api: vi.fn(), getAccessToken: vi.fn(() => null), setAccessToken: vi.fn() }));

const admin = { id: 'admin-1', email: 'owner@example.test', fullName: 'Taylor Owner', role: 'COMPANY_ADMIN', companyName: 'Example Co', companySlug: 'example-co' };

describe('SupportIQ authentication pages', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/login');
    api.mockImplementation(async path => {
      if (path === '/api/auth/refresh') throw new Error('No session');
      if (path === '/api/company/profile') return { name: 'Example Co', business_email: 'owner@example.test', slug: 'example-co' };
      throw new Error(`Unexpected API request: ${path}`);
    });
  });
  afterEach(() => { cleanup(); sessionStorage.clear(); });

  it('signs in and sends a company administrator to onboarding', async () => {
    const user = userEvent.setup();
    api.mockImplementation(async path => {
      if (path === '/api/auth/refresh') throw new Error('No session');
      if (path === '/api/auth/login') return { accessToken: 'short-lived-access', user: admin };
      if (path === '/api/company/profile') return { name: 'Example Co', business_email: 'owner@example.test', slug: 'example-co' };
      throw new Error(`Unexpected API request: ${path}`);
    });
    render(<AuthApp />);
    await user.type(screen.getByLabelText('Business email'), admin.email);
    await user.type(screen.getByLabelText('Password'), 'Password12345!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText(/your workspace is ready/i)).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST' }));
  });

  it('registers a workspace and confirms the successful onboarding route', async () => {
    window.history.replaceState({}, '', '/register');
    const user = userEvent.setup();
    api.mockImplementation(async path => {
      if (path === '/api/auth/refresh') throw new Error('No session');
      if (path === '/api/auth/register-company') return { accessToken: 'short-lived-access', user: admin };
      if (path === '/api/company/profile') return { name: 'Example Co', business_email: 'owner@example.test', slug: 'example-co' };
      throw new Error(`Unexpected API request: ${path}`);
    });
    render(<AuthApp />);
    await user.type(screen.getByLabelText('Company name'), 'Example Co');
    await user.type(screen.getByLabelText('Business email'), admin.email);
    await user.type(screen.getByLabelText('Administrator name'), admin.fullName);
    await user.type(screen.getByLabelText('Password (12 characters minimum)'), 'Password12345!');
    await user.type(screen.getByLabelText('Confirm password'), 'Password12345!');
    await user.click(screen.getByRole('button', { name: /create workspace/i }));
    expect(await screen.findByText(/your workspace is ready/i)).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith('/api/auth/register-company', expect.objectContaining({ method: 'POST' }));
  });

  it('redirects an unauthenticated visitor from the protected workspace to login', async () => {
    window.history.replaceState({}, '', '/app');
    render(<AuthApp />);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeInTheDocument());
  });

  it('shows a login failure without exposing protected workspace content', async () => {
    api.mockImplementation(async path => {
      if (path === '/api/auth/refresh') throw new Error('No session');
      if (path === '/api/auth/login') throw new Error('Email or password is incorrect.');
      throw new Error(`Unexpected API request: ${path}`);
    });
    const user = userEvent.setup();
    render(<AuthApp />);
    await user.type(screen.getByLabelText('Business email'), 'unknown@example.test');
    await user.type(screen.getByLabelText('Password'), 'WrongPassword123!');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect.');
    expect(screen.queryByText(/your workspace is ready/i)).not.toBeInTheDocument();
  });

  it('blocks workspace registration when the password confirmation differs', async () => {
    window.history.replaceState({}, '', '/register');
    const user = userEvent.setup();
    render(<AuthApp />);
    await user.type(screen.getByLabelText('Company name'), 'Example Co');
    await user.type(screen.getByLabelText('Business email'), 'owner@example.test');
    await user.type(screen.getByLabelText('Administrator name'), 'Taylor Owner');
    await user.type(screen.getByLabelText('Password (12 characters minimum)'), 'Password12345!');
    await user.type(screen.getByLabelText('Confirm password'), 'Different12345!');
    await user.click(screen.getByRole('button', { name: /create workspace/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Passwords do not match.');
    expect(api).not.toHaveBeenCalledWith('/api/auth/register-company', expect.anything());
  });
});
