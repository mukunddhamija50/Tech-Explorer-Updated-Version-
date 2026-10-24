const authAPI = '';
const appOrigin = 'http://127.0.0.1:8000';
const authMessage = document.getElementById('authMessage');
const nextPage = new URLSearchParams(location.search).get('next') === 'skillmatch.html'
  ? 'skillmatch.html' : 'index.html';
let pendingIdentifier = '';
let verifiedToken = '';

function setSignupStep(activeId) {
  for (const id of ['signupIdentityStep', 'signupVerifyStep', 'signupPasswordStep']) {
    const panel = document.getElementById(id);
    const active = id === activeId;
    panel.hidden = !active;
    panel.querySelectorAll('input').forEach(input => { input.disabled = !active; });
  }
}

function showMessage(message, success = false) {
  authMessage.textContent = message;
  authMessage.classList.toggle('success', success);
}

function setBusy(button, busy, label) {
  if (busy) {
    button.dataset.originalLabel = button.textContent;
    button.textContent = label;
  } else {
    button.textContent = button.dataset.originalLabel || label;
  }
  button.disabled = busy;
}

async function authRequest(path, payload, token) {
  const headers = {'Content-Type': 'application/json'};
  if (token) headers.Authorization = `Bearer ${token}`;
  let response;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    response = await fetch(authAPI + path, {
      method: payload === null ? 'GET' : 'POST',
      headers,
      credentials: 'same-origin',
      signal: controller.signal,
      ...(payload === null ? {} : {body: JSON.stringify(payload)})
    });
  } catch (error) {
    if (error.name === 'AbortError')
      throw new Error('The Skill Match server did not respond in time. Start it with Start SkillMatch.bat, then retry.');
    if (error instanceof TypeError)
      throw new Error('Cannot connect to Skill Match. Start the app with Start SkillMatch.bat, then refresh this page.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
  const responseBody = await response.text();
  let result;
  try {
    result = responseBody ? JSON.parse(responseBody) : {};
  } catch {
    const detail = responseBody.trim().slice(0, 180);
    throw new Error(detail || `The server returned an unreadable response (HTTP ${response.status}).`);
  }
  if (!response.ok) throw new Error(result.detail || `Request failed (${response.status}).`);
  return result;
}

function openTab(tab) {
  const signup = tab === 'signup';
  document.getElementById('loginForm').hidden = signup;
  document.getElementById('signupForm').hidden = !signup;
  document.getElementById('loginTab').classList.toggle('selected', !signup);
  document.getElementById('signupTab').classList.toggle('selected', signup);
  document.getElementById('loginTab').setAttribute('aria-selected', String(!signup));
  document.getElementById('signupTab').setAttribute('aria-selected', String(signup));
  document.getElementById('authHeading').textContent = signup ? 'Create your account' : 'Welcome back';
  showMessage('');
}

function saveLogin(result) {
  location.replace(nextPage);
}

document.getElementById('loginTab').addEventListener('click', () => openTab('login'));
document.getElementById('signupTab').addEventListener('click', () => openTab('signup'));

document.getElementById('loginForm').addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector('[type="submit"]');
  setBusy(button, true, 'Signing in…');
  showMessage('');
  try {
    const result = await authRequest('/api/auth/login', {
      identifier: document.getElementById('loginIdentifier').value.trim(),
      password: document.getElementById('loginPassword').value
    });
    saveLogin(result);
  } catch (error) {
    showMessage(error.message);
  } finally {
    setBusy(button, false, 'Log in');
  }
});

document.getElementById('sendOtpButton').addEventListener('click', async event => {
  const button = event.currentTarget;
  const field = document.getElementById('signupIdentifier');
  if (!field.reportValidity()) return;
  pendingIdentifier = field.value.trim();
  setBusy(button, true, 'Sending code…');
  showMessage('');
  try {
    const result = await authRequest('/api/auth/otp/request', {
      identifier: pendingIdentifier,
      purpose: 'signup'
    });
    setSignupStep('signupVerifyStep');
    document.getElementById('otpSentMessage').textContent = result.development_code
      ? `${result.message} Verification code: ${result.development_code}`
      : result.message;
    document.getElementById('signupCode').focus();
    showMessage('');
  } catch (error) {
    showMessage(error.message);
  } finally {
    setBusy(button, false, 'Send verification code');
  }
});

document.getElementById('verifyOtpButton').addEventListener('click', async event => {
  const button = event.currentTarget;
  const field = document.getElementById('signupCode');
  if (!field.reportValidity()) return;
  setBusy(button, true, 'Verifying…');
  showMessage('');
  try {
    const result = await authRequest('/api/auth/otp/verify', {
      identifier: pendingIdentifier,
      code: field.value.trim()
    });
    verifiedToken = result.verification_token;
    setSignupStep('signupPasswordStep');
    document.getElementById('signupName').focus();
  } catch (error) {
    showMessage(error.message);
  } finally {
    setBusy(button, false, 'Verify code');
  }
});

document.getElementById('changeIdentifierButton').addEventListener('click', () => {
  pendingIdentifier = '';
  verifiedToken = '';
  document.getElementById('signupCode').value = '';
  setSignupStep('signupIdentityStep');
  document.getElementById('signupIdentifier').focus();
  showMessage('');
});

document.getElementById('signupForm').addEventListener('submit', async event => {
  event.preventDefault();
  if (!verifiedToken) return showMessage('Verify your email or phone number first.');
  const button = event.currentTarget.querySelector('#signupPasswordStep [type="submit"]');
  setBusy(button, true, 'Creating account…');
  showMessage('');
  try {
    const result = await authRequest('/api/auth/signup/complete', {
      identifier: pendingIdentifier,
      verification_token: verifiedToken,
      name: document.getElementById('signupName').value.trim(),
      password: document.getElementById('signupPassword').value
    });
    saveLogin(result);
  } catch (error) {
    showMessage(error.message);
  } finally {
    setBusy(button, false, 'Create account');
  }
});

async function setupGoogleSignIn() {
  try {
    const config = await authRequest('/api/auth/config', null);
    if (!config.google_client_id) {
      document.getElementById('googleUnavailable').hidden = false;
      return;
    }
    const script = document.createElement('script');
    script.src = 'https://accounts.google.com/gsi/client';
    script.async = true;
    script.defer = true;
    script.onload = () => {
      if (!window.google?.accounts?.id) {
        showMessage('Google sign-in could not load. Check your connection and retry.');
        return;
      }
      window.google.accounts.id.initialize({
        client_id: config.google_client_id,
        callback: async response => {
          showMessage('');
          try {
            saveLogin(await authRequest('/api/auth/google', {credential: response.credential}));
          } catch (error) {
            showMessage(error.message);
          }
        }
      });
      window.google.accounts.id.renderButton(document.getElementById('googleButton'), {
        theme: 'outline', size: 'large', text: 'continue_with', shape: 'rectangular', width: 360
      });
    };
    script.onerror = () => showMessage('Google sign-in could not load. Check your connection and retry.');
    document.head.appendChild(script);
  } catch (error) {
    showMessage(error.message);
  }
}

async function resumeExistingSession() {
  try {
    const result = await authRequest('/api/auth/session', null);
    if (result.authenticated) location.replace(nextPage);
  } catch (error) {
    showMessage(error.message);
  }
}

const initialTab = location.hash === '#signup' ? 'signup' : 'login';
openTab(initialTab);

async function initializeAuthPage() {
  if (location.protocol === 'file:') {
    const destination = new URL(location.pathname.split('/').pop() || 'auth.html', appOrigin);
    destination.search = location.search;
    destination.hash = location.hash;
    location.replace(destination.href);
    return;
  }
  setupGoogleSignIn();
  resumeExistingSession();
}

initializeAuthPage();
