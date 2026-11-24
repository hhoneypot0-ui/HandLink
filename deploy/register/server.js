#!/usr/bin/env node
/**
 * 牵手远程 — 注册服务
 *
 * 用途：让用户自己注册账号，不必管理员手动建。
 * 原理：用管理员身份调用信令服务的管理接口建号，并限制总账号数。
 *
 * 监听：9444 (HTTPS，复用 /opt/handlink-tls 的证书)
 * 接口：
 *   POST /api/register        {username, password}
 *   GET  /api/register/status 返回 {used, limit}
 *   GET  /healthz
 */

const https = require('https');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || 9444);
const UPSTREAM_PORT = Number(process.env.UPSTREAM_PORT || 8443);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.ADMIN_PASS || 'admin123';
const LIMIT = Number(process.env.LIMIT ?? 10); // 0 或负数 = 不限
const CERT = process.env.CERT || '/opt/handlink-tls/server.crt';
const KEY = process.env.KEY || '/opt/handlink-tls/server.key';
const BIND_FILE = process.env.BIND_FILE || '/opt/handlink-register/bindings.json';

// ---------- 调用上游信令服务（自签证书，跳过校验） ----------
function upstream(method, apiPath, body, token) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : JSON.stringify(body);
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = 'Bearer ' + token;
    if (payload) headers['Content-Length'] = Buffer.byteLength(payload);

    const req = https.request({
      host: '127.0.0.1',
      port: UPSTREAM_PORT,
      path: apiPath,
      method,
      headers,
      rejectUnauthorized: false,
      timeout: 10000,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch (_) {}
        resolve({ status: res.statusCode, text, json });
      });
    });
    req.on('timeout', () => { req.destroy(new Error('upstream timeout')); });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

// ---------- 管理员 token 缓存 ----------
let adminToken = null;
let adminTokenAt = 0;

async function getAdminToken(force) {
  if (!force && adminToken && Date.now() - adminTokenAt < 10 * 60 * 1000) {
    return adminToken;
  }
  const r = await upstream('POST', '/api/login', {
    username: ADMIN_USER,
    password: ADMIN_PASS,
  });
  if (r.status !== 200 || !r.json || !r.json.token) {
    adminToken = null;
    throw new Error('admin login failed: ' + r.status);
  }
  adminToken = r.json.token;
  adminTokenAt = Date.now();
  return adminToken;
}

async function callAdmin(method, apiPath, body) {
  let token = await getAdminToken(false);
  let r = await upstream(method, apiPath, body, token);
  if (r.status === 401 || r.status === 403) {
    token = await getAdminToken(true);
    r = await upstream(method, apiPath, body, token);
  }
  return r;
}

// ---------- 限流：每个 IP 每小时最多 10 次注册尝试 ----------
const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const arr = (hits.get(ip) || []).filter((t) => now - t < 3600 * 1000);
  if (arr.length >= 10) { hits.set(ip, arr); return true; }
  arr.push(now);
  hits.set(ip, arr);
  return false;
}

function validUsername(u) {
  return typeof u === 'string' && /^[A-Za-z0-9_]{3,20}$/.test(u);
}
function validPassword(p) {
  return typeof p === 'string' && p.length >= 6 && p.length <= 64;
}

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function readBody(req, limitBytes = 4096) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > limitBytes) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      try { resolve(JSON.parse(text || '{}')); }
      catch (_) { resolve({}); }
    });
    req.on('error', reject);
  });
}

function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}

/**
 * 给新账号授予全部设备（10 年通配租约）。
 * 不授予的话，新账号登录后设备列表是空的——看起来就是「用不了」。
 */
async function grantAllDevices(username) {
  const r = await callAdmin('POST', '/api/admin/leases/create', {
    username,
    device_id: '*',
    duration_seconds: 10 * 365 * 24 * 3600,
    note: 'auto: 注册自动授权全部设备',
  });
  if (r.status !== 200) {
    console.log(`[register] lease grant failed user=${username} status=${r.status} ${r.text.slice(0, 120)}`);
    return false;
  }
  return true;
}

// ---------- 账号 ↔ 手机 绑定（一个账号只能一台手机） ----------
function readBindings() {
  try {
    return JSON.parse(fs.readFileSync(BIND_FILE, 'utf8'));
  } catch (_) {
    return {};
  }
}

function writeBindings(obj) {
  try {
    fs.mkdirSync(path.dirname(BIND_FILE), { recursive: true });
    fs.writeFileSync(BIND_FILE, JSON.stringify(obj, null, 2));
  } catch (e) {
    console.log('[bind] write failed', e.message);
  }
}

// ---------- 账号数量 ----------
async function countUsers() {
  const r = await callAdmin('GET', '/api/admin/users');
  if (r.status !== 200 || !Array.isArray(r.json)) return -1;
  return r.json.length;
}

// ---------- 路由 ----------
const server = https.createServer({
  cert: fs.readFileSync(CERT),
  key: fs.readFileSync(KEY),
}, async (req, res) => {
  const url = new URL(req.url, 'https://127.0.0.1');
  const ip = clientIp(req);

  if (req.method === 'OPTIONS') return send(res, 204, {});

  if (url.pathname === '/healthz') {
    return send(res, 200, { ok: true, ts: Date.now() });
  }

  if (url.pathname === '/api/register/status' && req.method === 'GET') {
    try {
      const used = await countUsers();
      return send(res, 200, { used, limit: LIMIT, open: LIMIT <= 0 || used < LIMIT });
    } catch (e) {
      return send(res, 500, { error: '服务器暂时不可用' });
    }
  }

  // 登录后调用：声明「这台手机正在用这个账号」
  // 同一个账号后登录的会顶掉前一个，前一台会被要求下线（不做永久绑定，换手机不受限）
  if (url.pathname === '/api/session/claim' && req.method === 'POST') {
    let body;
    try { body = await readBody(req); } catch (_) { return send(res, 400, { error: '请求格式错误' }); }
    const username = String(body.username || '').trim();
    const token = String(body.token || '').trim();
    if (!username || !token) return send(res, 400, { error: '缺少参数' });
    const deviceId = String(body.device_id || '').trim();
    const deviceName = String(body.device_name || '').trim();

    const binds = readBindings();
    const prev = binds[username];
    binds[username] = {
      token,
      device_id: deviceId,
      device_name: deviceName,
      at: Date.now(),
    };
    writeBindings(binds);
    console.log(`[session] ${username} 现在在 ${deviceName || deviceId || '未知设备'}` +
      (prev && prev.device_id !== deviceId ? `（顶掉了 ${prev.device_name || prev.device_id}）` : ''));
    return send(res, 200, { ok: true, displaced: !!(prev && prev.device_id !== deviceId) });
  }

  // 客户端定时自检：自己还是不是当前会话
  if (url.pathname === '/api/session/check' && req.method === 'GET') {
    const username = url.searchParams.get('username') || '';
    const token = url.searchParams.get('token') || '';
    const cur = readBindings()[username];
    if (!cur) {
      // 没有记录 = 这台手机还没声明过（或服务重启过），不能当成被顶掉
      return send(res, 200, { active: true, known: false, device_name: '' });
    }
    return send(res, 200, {
      active: cur.token === token,
      known: true,
      device_name: cur.device_name || '',
    });
  }

  // 主动下线（退出登录时调用）
  if (url.pathname === '/api/session/release' && req.method === 'POST') {
    let body;
    try { body = await readBody(req); } catch (_) { return send(res, 400, { error: '请求格式错误' }); }
    const username = String(body.username || '').trim();
    const token = String(body.token || '').trim();
    const binds = readBindings();
    if (binds[username] && binds[username].token === token) {
      delete binds[username];
      writeBindings(binds);
    }
    return send(res, 200, { ok: true });
  }

  if (url.pathname === '/api/register' && req.method === 'POST') {
    if (rateLimited(ip)) {
      return send(res, 429, { error: '尝试太频繁，请稍后再试' });
    }
    let body;
    try { body = await readBody(req); }
    catch (_) { return send(res, 400, { error: '请求格式错误' }); }

    const username = String(body.username || '').trim();
    const password = String(body.password || '');

    if (!validUsername(username)) {
      return send(res, 400, { error: '账号需 3-20 位，只能用字母、数字、下划线' });
    }
    if (!validPassword(password)) {
      return send(res, 400, { error: '密码至少 6 位' });
    }

    try {
      const used = await countUsers();
      if (LIMIT > 0 && used >= LIMIT) {
        return send(res, 403, { error: `注册名额已满（${LIMIT} 个），请联系管理员` });
      }

      const r = await callAdmin('POST', '/api/admin/users/create', {
        username,
        password,
        role: 'user',
        assigned_devices: ['*'],
      });

      if (r.status === 200 || r.status === 201) {
        const granted = await grantAllDevices(username);
        console.log(`[register] ok user=${username} ip=${ip} (used ${used + 1}/${LIMIT}) lease=${granted}`);
        return send(res, 200, { ok: true, username, granted });
      }
      if (/already exists/i.test(r.text)) {
        return send(res, 409, { error: '这个账号已经有人用了，换一个' });
      }
      console.log(`[register] fail user=${username} status=${r.status} body=${r.text.slice(0, 200)}`);
      return send(res, 500, { error: '注册失败，请稍后再试' });
    } catch (e) {
      console.log('[register] error', e.message);
      return send(res, 500, { error: '服务器暂时不可用' });
    }
  }

  send(res, 404, { error: 'not found' });
});

/**
 * 巡视为每个普通账号补齐「全部设备」的租约。
 * 服务器对 "*" 只允许一条租约，所以改成按真实设备逐台授权。
 */
async function ensureLeases() {
  try {
    const token = await getAdminToken(false);
    // 设备列表（装在容器里的注册表）
    const { execFile } = require('child_process');
    const raw = await new Promise((resolve) => {
      execFile('docker', ['exec', 'cp-aio', 'cat', '/app/data/devices.json'], (err, stdout) => {
        resolve(err ? '{}' : stdout);
      });
    });
    let devices = [];
    try { devices = Object.keys(JSON.parse(raw)); } catch (_) { devices = []; }
    if (!devices.length) return;

    const usersResp = await upstream('GET', '/api/admin/users', undefined, token);
    const users = Array.isArray(usersResp.json) ? usersResp.json : [];
    const leasesResp = await upstream('GET', '/api/admin/leases?status=all', undefined, token);
    const leases = Array.isArray(leasesResp.json) ? leasesResp.json : [];

    const have = new Set();
    for (const l of leases) {
      if (l && l.active) have.add(l.username + '|' + l.device_id);
    }

    let added = 0;
    for (const u of users) {
      if (!u || !u.username || u.role === 'admin') continue;
      for (const d of devices) {
        if (have.has(u.username + '|' + d)) continue;
        const r = await upstream('POST', '/api/admin/leases/create', {
          username: u.username,
          device_id: d,
          duration_seconds: 10 * 365 * 24 * 3600,
          note: 'auto: 全部设备',
        }, token);
        if (r.status === 200) added++;
      }
    }
    if (added) console.log(`[leases] 新增 ${added} 条设备授权`);
  } catch (e) {
    console.log('[leases] 巡检失败', e.message);
  }
}

setInterval(ensureLeases, 60 * 1000);
setTimeout(ensureLeases, 3000);

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[register] listening on :${PORT}, upstream 127.0.0.1:${UPSTREAM_PORT}, limit=${LIMIT}`);
});
