#!/usr/bin/env node
/**
 * Mock trading backend. Zero dependencies (Node 18+).
 *
 * Protocols:
 *   - REST/JSON over HTTP for request/response data (stock list, details, portfolio, orders).
 *   - WebSocket (RFC 6455) for streaming live prices (server push, ~1 tick/sec).
 *   - Server-Sent Events for the market news feed (one-way push, resumable via Last-Event-ID).
 *
 * Simulated network conditions (env vars):
 *   LATENCY_MS    base delay added to every REST response and WS push   (default 1500)
 *   JITTER_MS     random extra delay 0..JITTER_MS                       (default 1000)
 *   FAILURE_RATE  probability a REST call fails with 503 / dropped conn (default 0.1);
 *                 when > 0, SSE streams are also dropped every 30–90s to exercise resume
 *   EXTRA_STOCKS  number of synthetic stocks appended to the list, for perf tests (default 0)
 *   MOCK_PORT     (default 8080)
 *
 * Run:  node mock-server/server.js
 *       LATENCY_MS=3000 FAILURE_RATE=0.3 node mock-server/server.js
 */
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.MOCK_PORT || 8080);
const LATENCY_MS = Number(process.env.LATENCY_MS ?? 1500);
const JITTER_MS = Number(process.env.JITTER_MS ?? 1000);
const FAILURE_RATE = Number(process.env.FAILURE_RATE ?? 0.1);
const EXTRA_STOCKS = Number(process.env.EXTRA_STOCKS ?? 0);
const TICK_MS = 1000;
const HISTORY_POINTS = 120;

// ---------------------------------------------------------------------------
// Market data
// ---------------------------------------------------------------------------
const SEED = [
  ['AAPL', 'Apple Inc.', 'Technology', 228.4, 3.45e12, 34.1, 'Designs consumer electronics, software and services including iPhone, Mac and iPad.'],
  ['MSFT', 'Microsoft Corp.', 'Technology', 432.1, 3.21e12, 36.2, 'Develops software, cloud services (Azure), and devices.'],
  ['GOOGL', 'Alphabet Inc.', 'Communication', 171.9, 2.12e12, 24.8, 'Parent of Google: search, advertising, YouTube, Android and Cloud.'],
  ['AMZN', 'Amazon.com Inc.', 'Consumer', 192.3, 2.01e12, 41.5, 'E-commerce, AWS cloud computing, digital streaming and devices.'],
  ['NVDA', 'NVIDIA Corp.', 'Technology', 135.6, 3.33e12, 63.7, 'Designs GPUs and accelerated computing platforms for AI and gaming.'],
  ['TSLA', 'Tesla Inc.', 'Automotive', 248.9, 7.95e11, 68.3, 'Electric vehicles, energy storage and solar products.'],
  ['META', 'Meta Platforms', 'Communication', 571.2, 1.45e12, 29.4, 'Social platforms (Facebook, Instagram, WhatsApp) and Reality Labs.'],
  ['NFLX', 'Netflix Inc.', 'Communication', 702.5, 3.02e11, 44.0, 'Subscription streaming service for films and series.'],
  ['AMD', 'Advanced Micro Devices', 'Technology', 156.7, 2.54e11, 128.9, 'CPUs, GPUs and adaptive SoCs for data center, PC and embedded.'],
  ['JPM', 'JPMorgan Chase & Co.', 'Financials', 221.8, 6.31e11, 12.3, 'Global investment banking, financial services and asset management.'],
  ['V', 'Visa Inc.', 'Financials', 289.6, 5.62e11, 30.1, 'Global payments technology and card network.'],
  ['KO', 'Coca-Cola Co.', 'Consumer Staples', 69.8, 3.01e11, 27.6, 'Beverage company with a portfolio of soft drink and water brands.'],
];
// Synthetic tickers so the list is long enough to scroll in performance tests.
for (let i = 1; i <= EXTRA_STOCKS; i++) {
  const sym = `SYN${String(i).padStart(3, '0')}`;
  SEED.push([sym, `Synthetic Corp ${i}`, 'Synthetic', 20 + ((i * 37) % 480), 1e10 * (1 + (i % 50)), 10 + (i % 40), 'Synthetic stock generated for performance testing.']);
}

const round2 = (n) => Math.round(n * 100) / 100;
const gaussian = () => {
  const u = 1 - Math.random();
  const v = Math.random();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
};

const stocks = new Map();
for (const [symbol, name, sector, price, marketCap, pe, description] of SEED) {
  const prevClose = round2(price * (1 + gaussian() * 0.01));
  const s = {
    symbol, name, sector, description, marketCap, pe,
    prevClose,
    open: round2(prevClose * (1 + gaussian() * 0.005)),
    price,
    high: price, low: price,
    volume: Math.floor(5e6 + Math.random() * 3e7),
    week52High: round2(price * (1.1 + Math.random() * 0.25)),
    week52Low: round2(price * (0.6 + Math.random() * 0.2)),
    history: [],
  };
  // Back-fill a short history so charts render immediately.
  let p = s.open;
  for (let i = 0; i < HISTORY_POINTS; i++) {
    p = round2(p * (1 + gaussian() * 0.0015));
    s.history.push(p);
  }
  s.price = p;
  s.high = Math.max(s.open, ...s.history);
  s.low = Math.min(s.open, ...s.history);
  stocks.set(symbol, s);
}

function tick() {
  for (const s of stocks.values()) {
    s.price = Math.max(0.01, round2(s.price * (1 + gaussian() * 0.0015)));
    s.high = Math.max(s.high, s.price);
    s.low = Math.min(s.low, s.price);
    s.volume += Math.floor(Math.random() * 20000);
    s.history.push(s.price);
    if (s.history.length > HISTORY_POINTS) s.history.shift();
  }
}

const quoteOf = (s) => ({
  symbol: s.symbol,
  name: s.name,
  price: s.price,
  change: round2(s.price - s.prevClose),
  changePercent: round2(((s.price - s.prevClose) / s.prevClose) * 100),
});

const detailOf = (s) => ({
  ...quoteOf(s),
  sector: s.sector,
  description: s.description,
  open: s.open,
  high: s.high,
  low: s.low,
  prevClose: s.prevClose,
  volume: s.volume,
  marketCap: s.marketCap,
  peRatio: s.pe,
  week52High: s.week52High,
  week52Low: s.week52Low,
  history: s.history,
});

// ---------------------------------------------------------------------------
// Portfolio & orders (single demo user, in memory)
// ---------------------------------------------------------------------------
const account = {
  cash: 25000,
  holdings: new Map([
    ['AAPL', { quantity: 20, avgPrice: 210.15 }],
    ['NVDA', { quantity: 35, avgPrice: 118.4 }],
    ['KO', { quantity: 50, avgPrice: 64.2 }],
  ]),
};
const ordersByIdempotencyKey = new Map();

function portfolioSnapshot() {
  const holdings = [...account.holdings.entries()].map(([symbol, h]) => {
    const s = stocks.get(symbol);
    return { symbol, name: s.name, quantity: h.quantity, avgPrice: h.avgPrice, lastPrice: s.price };
  });
  return { cash: round2(account.cash), holdings };
}

function placeOrder({ symbol, side, quantity }) {
  const s = stocks.get(symbol);
  if (!s) return [404, { error: 'UNKNOWN_SYMBOL', message: `Unknown symbol ${symbol}` }];
  if (!Number.isInteger(quantity) || quantity <= 0) {
    return [400, { error: 'INVALID_QUANTITY', message: 'Quantity must be a positive integer' }];
  }
  const price = s.price; // market order fills at current price
  const cost = round2(price * quantity);
  const h = account.holdings.get(symbol);

  if (side === 'BUY') {
    if (cost > account.cash) return [422, { error: 'INSUFFICIENT_FUNDS', message: 'Not enough cash for this order' }];
    account.cash -= cost;
    if (h) {
      h.avgPrice = round2((h.avgPrice * h.quantity + cost) / (h.quantity + quantity));
      h.quantity += quantity;
    } else {
      account.holdings.set(symbol, { quantity, avgPrice: price });
    }
  } else if (side === 'SELL') {
    if (!h || h.quantity < quantity) return [422, { error: 'INSUFFICIENT_SHARES', message: 'Not enough shares to sell' }];
    account.cash += cost;
    h.quantity -= quantity;
    if (h.quantity === 0) account.holdings.delete(symbol);
  } else {
    return [400, { error: 'INVALID_SIDE', message: 'side must be BUY or SELL' }];
  }

  return [201, {
    orderId: crypto.randomUUID(),
    symbol, side, quantity,
    filledPrice: price,
    total: cost,
    status: 'FILLED',
    filledAt: new Date().toISOString(),
    portfolio: portfolioSnapshot(),
  }];
}

// ---------------------------------------------------------------------------
// HTTP (REST)
// ---------------------------------------------------------------------------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const networkDelay = () => LATENCY_MS + Math.random() * JITTER_MS;

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(json),
    'Cache-Control': 'no-store',
  });
  res.end(json);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', () => {
      try { resolve(JSON.parse(data || '{}')); } catch { resolve(null); }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const started = Date.now();
  res.on('finish', () => {
    const key = req.headers['idempotency-key'];
    const replay = res.getHeader('Idempotent-Replay') ? ' REPLAY' : '';
    console.log(`${req.method} ${url.pathname} -> ${res.statusCode} (${Date.now() - started}ms)${key ? ` key=${key.slice(0, 8)}${replay}` : ''}`);
  });

  await sleep(networkDelay());

  // Random transient failure *before* processing (safe to retry).
  if (Math.random() < FAILURE_RATE / 2) {
    res.setHeader('Retry-After', '1');
    return send(res, 503, { error: 'SERVICE_UNAVAILABLE', message: 'Simulated transient failure' });
  }

  try {
    if (req.method === 'GET' && url.pathname === '/sse/news') {
      return openNewsStream(req, res);
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/stocks') {
      return send(res, 200, { stocks: [...stocks.values()].map(quoteOf), asOf: Date.now() });
    }

    const detailMatch = url.pathname.match(/^\/api\/v1\/stocks\/([A-Za-z.]+)$/);
    if (req.method === 'GET' && detailMatch) {
      const s = stocks.get(detailMatch[1].toUpperCase());
      if (!s) return send(res, 404, { error: 'UNKNOWN_SYMBOL', message: 'Stock not found' });
      return send(res, 200, detailOf(s));
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/portfolio') {
      return send(res, 200, portfolioSnapshot());
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/orders') {
      const key = req.headers['idempotency-key'];
      if (!key) return send(res, 400, { error: 'MISSING_IDEMPOTENCY_KEY', message: 'Idempotency-Key header is required' });

      // Replay: the client retried after a timeout / dropped response. Return the original result.
      if (ordersByIdempotencyKey.has(key)) {
        const [status, body] = ordersByIdempotencyKey.get(key);
        res.setHeader('Idempotent-Replay', 'true');
        return send(res, status, body);
      }

      const body = await readBody(req);
      if (!body) return send(res, 400, { error: 'BAD_JSON', message: 'Invalid JSON body' });
      const result = placeOrder(body);
      ordersByIdempotencyKey.set(key, result);

      // Random failure *after* processing: order executed but the response is lost.
      // Clients must retry with the same Idempotency-Key to avoid a duplicate order.
      if (Math.random() < FAILURE_RATE / 2) {
        console.log(`  ! dropping response for order ${key} (already executed)`);
        return req.socket.destroy();
      }
      return send(res, result[0], result[1]);
    }

    send(res, 404, { error: 'NOT_FOUND', message: 'No such endpoint' });
  } catch (e) {
    console.error(e);
    send(res, 500, { error: 'INTERNAL', message: e.message });
  }
});

// ---------------------------------------------------------------------------
// WebSocket (minimal RFC 6455 server: text frames, ping/pong, close)
// ---------------------------------------------------------------------------
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const wsClients = new Set();

function encodeFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x80 | opcode, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | opcode; header[1] = 126; header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | opcode; header[1] = 127; header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

function wsSendJson(socket, obj) {
  if (!socket.destroyed) socket.write(encodeFrame(0x1, Buffer.from(JSON.stringify(obj))));
}

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const key = req.headers['sec-websocket-key'];
  if (url.pathname !== '/ws/prices' || !key) return socket.destroy();

  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
    `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  );
  wsClients.add(socket);
  console.log(`WS connected (${wsClients.size} clients)`);

  // Full snapshot on connect so a (re)connecting client is immediately consistent.
  setTimeout(() => wsSendJson(socket, { type: 'snapshot', ts: Date.now(), quotes: [...stocks.values()].map(quoteOf) }), networkDelay());

  let buf = Buffer.alloc(0);
  socket.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    while (buf.length >= 2) {
      const opcode = buf[0] & 0x0f;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f;
      let offset = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); offset = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); offset = 10; }
      const maskLen = masked ? 4 : 0;
      if (buf.length < offset + maskLen + len) return;
      const mask = masked ? buf.subarray(offset, offset + 4) : null;
      const payload = Buffer.from(buf.subarray(offset + maskLen, offset + maskLen + len));
      if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      buf = buf.subarray(offset + maskLen + len);

      if (opcode === 0x8) { socket.end(encodeFrame(0x8, payload.subarray(0, 2))); return; } // close
      if (opcode === 0x9) socket.write(encodeFrame(0xA, payload)); // ping -> pong
      // Text frames from client are ignored: every client receives all symbols.
    }
  });
  const drop = () => { if (wsClients.delete(socket)) console.log(`WS disconnected (${wsClients.size} clients)`); };
  socket.on('close', drop);
  socket.on('error', drop);
});

// ---------------------------------------------------------------------------
// Server-Sent Events (market news)
// ---------------------------------------------------------------------------
const NEWS_BACKLOG = 50;           // ring buffer kept for Last-Event-ID replay
const NEWS_INITIAL = 20;           // items a brand-new client receives on connect
const SSE_HEARTBEAT_MS = 15000;
const news = [];                   // oldest first
const sseClients = new Set();
let newsSeq = 0;

const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];
const newsStocks = () => [...stocks.values()].filter((s) => s.sector !== 'Synthetic');

const STOCK_NEWS = [
  ['EARNINGS', 1, (s) => `${s.name} beats quarterly earnings estimates`, (s) => `Revenue and EPS came in ahead of consensus; ${s.symbol} guidance raised for the full year.`],
  ['EARNINGS', -1, (s) => `${s.name} misses quarterly revenue estimates`, (s) => `Sales fell short of expectations as ${s.sector.toLowerCase()} demand softened.`],
  ['ANALYST', 1, (s) => `Analyst upgrades ${s.name} to Buy`, (s) => `Price target raised to $${round2(s.price * 1.18)} on improving margins.`],
  ['ANALYST', -1, (s) => `Analyst downgrades ${s.name} to Neutral`, () => 'Valuation cited after a strong run; target trimmed.'],
  ['ANALYST', 0, (s) => `${s.name} initiated at Hold`, () => 'Coverage started with a balanced risk/reward view.'],
];
const MARKET_NEWS = [
  ['Treasury yields edge higher ahead of Fed minutes', 'Investors weigh the path of rate cuts into next year.'],
  ['Oil slips as inventories build more than expected', 'Energy names trade mixed in early action.'],
  ['Dollar steadies after a volatile week', 'Currency moves keep multinational earnings in focus.'],
  ['Small caps outperform as risk appetite returns', 'Breadth improves across sectors.'],
  ['Jobless claims come in below forecasts', 'Labor market remains resilient despite tighter policy.'],
];

function publishNews(item, ts = Date.now()) {
  const entry = { id: String(++newsSeq), ts, sentiment: 0, ...item };
  news.push(entry);
  if (news.length > NEWS_BACKLOG) news.shift();
  for (const res of sseClients) sseWrite(res, entry);
}

function randomNews() {
  if (Math.random() < 0.3) {
    const [headline, summary] = pick(MARKET_NEWS);
    return { category: 'MARKET', headline, summary };
  }
  const s = pick(newsStocks());
  const [category, sentiment, headline, summary] = pick(STOCK_NEWS);
  return { category, sentiment, symbol: s.symbol, headline: headline(s), summary: summary(s) };
}

// Price alerts fire each time a stock reaches a new whole-percent move on the day.
function checkPriceAlerts() {
  for (const s of newsStocks()) {
    const pct = ((s.price - s.prevClose) / s.prevClose) * 100;
    const level = Math.trunc(pct);
    const prev = s.alertLevel ?? 0;
    if (level === 0 || (Math.sign(level) === Math.sign(prev) && Math.abs(level) <= Math.abs(prev))) continue;
    s.alertLevel = level;
    const change = round2(s.price - s.prevClose);
    publishNews({
      category: 'PRICE_ALERT',
      sentiment: Math.sign(level),
      symbol: s.symbol,
      headline: `${s.symbol} ${level > 0 ? 'up' : 'down'} ${Math.abs(level)}% today`,
      summary: `Trading at $${s.price}, ${change >= 0 ? '+' : '-'}$${Math.abs(change)} on the session.`,
    });
  }
}

function sseWrite(res, entry) {
  res.write(`id: ${entry.id}\nevent: news\ndata: ${JSON.stringify(entry)}\n\n`);
}

function openNewsStream(req, res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
  });

  // Resume: replay only what the client missed. Unknown/future ids (e.g. after a server restart)
  // are treated like a fresh client.
  const lastId = Number(req.headers['last-event-id']);
  const resuming = Number.isInteger(lastId) && lastId > 0 && lastId <= newsSeq;
  const backlog = resuming ? news.filter((n) => Number(n.id) > lastId) : news.slice(-NEWS_INITIAL);
  for (const n of backlog) sseWrite(res, n);
  sseClients.add(res);
  console.log(`SSE connected (${sseClients.size} clients)${resuming ? ` resumed after id=${lastId}, replayed ${backlog.length}` : ''}`);

  // Comments keep idle proxies from closing the stream and let clients detect dead links.
  const heartbeat = setInterval(() => res.write(': keep-alive\n\n'), SSE_HEARTBEAT_MS);
  // Simulated flaky link: drop the stream so clients exercise Last-Event-ID resume.
  const drop = FAILURE_RATE > 0 ? setTimeout(() => res.destroy(), 30000 + Math.random() * 60000) : null;

  res.on('close', () => {
    clearInterval(heartbeat);
    clearTimeout(drop);
    if (sseClients.delete(res)) console.log(`SSE disconnected (${sseClients.size} clients)`);
  });
}

// Baseline alert levels so startup prices don't trigger a burst of alerts.
for (const s of newsStocks()) s.alertLevel = Math.trunc(((s.price - s.prevClose) / s.prevClose) * 100);
// Seed a little history so the feed isn't empty on first open.
for (let i = 8; i > 0; i--) publishNews(randomNews(), Date.now() - i * 90_000);
(function scheduleNews() {
  setTimeout(() => { publishNews(randomNews()); scheduleNews(); }, 3000 + Math.random() * 4000);
})();

setInterval(() => {
  tick();
  checkPriceAlerts();
  if (wsClients.size === 0) return;
  const msg = { type: 'ticks', ts: Date.now(), quotes: [...stocks.values()].map(quoteOf) };
  // Push is delayed too, emulating a slow downstream link.
  for (const socket of wsClients) setTimeout(() => wsSendJson(socket, msg), networkDelay());
}, TICK_MS);

server.listen(PORT, () => {
  console.log(`Mock trading server on http://localhost:${PORT}`);
  console.log(`  REST: /api/v1/stocks, /api/v1/stocks/:symbol, /api/v1/portfolio, POST /api/v1/orders`);
  console.log(`  WS:   ws://localhost:${PORT}/ws/prices`);
  console.log(`  SSE:  http://localhost:${PORT}/sse/news`);
  console.log(`  latency=${LATENCY_MS}ms jitter=${JITTER_MS}ms failureRate=${FAILURE_RATE} stocks=${stocks.size}`);
});
