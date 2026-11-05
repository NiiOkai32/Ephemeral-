import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { pathToFileURL } from 'node:url';

// Relay ciphertext in memory only. No logs, database, message archive or invite secrets.
export function turnConfigFromEnv(env=process.env) {
  const urls=(env.TURN_URLS ?? '').split(',').map(s=>s.trim()).filter(Boolean);
  if(!urls.length || !env.TURN_USERNAME || !env.TURN_PASSWORD) return null;
  if(urls.length>8 || urls.some(url=>url.length>512 || !/^turns?:[^\s]+$/.test(url)) ||
     env.TURN_USERNAME.length>1024 || env.TURN_PASSWORD.length>1024)
    throw new Error('Invalid TURN server configuration');
  return [{urls,username:env.TURN_USERNAME,credential:env.TURN_PASSWORD}];
}
export function createRelay({iceServers=turnConfigFromEnv()}={}) {
  const rooms = new Map();
  const server = http.createServer((req, res) => {
    res.writeHead(req.url === '/health' ? 200 : 404, {'content-type':'application/json'});
    res.end(req.url === '/health' ? '{"status":"ok"}' : '{}');
  });
  const wss = new WebSocketServer({noServer:true, maxPayload:6500000, perMessageDeflate:false});
  server.on('upgrade', (req, socket, head) => {
    const match = /^\/room\/([a-f0-9]{64})$/.exec(req.url ?? '');
    if (!match || rooms.size >= 500 && !rooms.has(match[1])) {
      socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); socket.destroy(); return;
    }
    wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, match[1]));
  });
  wss.on('connection', (ws, roomId) => {
    const peers = rooms.get(roomId) ?? new Set();
    if (peers.size >= 2) { ws.close(1008, 'Room full'); return; }
    peers.add(ws); rooms.set(roomId, peers);
    let tokens = 12_000_000, updated = Date.now(), frames = 0, frameStart = Date.now();
    ws.alive = true;
    ws.on('pong', () => {ws.alive = true;});
    if (peers.size === 2) for (const peer of peers) peer.send('{"type":"peer"}');
    ws.on('message', (data, binary) => {
      const now = Date.now();
      tokens = Math.min(12_000_000, tokens + (now-updated)*2000); updated=now;
      if(now-frameStart>1000) {frames=0;frameStart=now;}
      if(binary || ++frames>150 || data.length>tokens) {ws.close(1008,'Rate limited'); return;}
      tokens-=data.length;
      try {
        const j=JSON.parse(data.toString());
        if(j.type==='ice') {
          if(peers.size!==2) {ws.send(JSON.stringify({type:'ice',error:'Join a paired conversation first'}));return;}
          if(ws.iceRequested) return;
          ws.iceRequested=true;
          ws.send(JSON.stringify(iceServers ? {type:'ice',iceServers} : {type:'ice',error:'Calling not configured'}));
          return;
        }
        if(j.type!=='data' ||typeof j.data!=='string'||j.data.length>6400000) throw Error();
        for(const peer of peers) if(peer!==ws && peer.readyState===WebSocket.OPEN) {
          if(peer.bufferedAmount>12_000_000) peer.close(1013,'Slow receiver');
          else peer.send(JSON.stringify({type:'data',data:j.data}));
        }
      } catch {ws.close(1008,'Invalid frame');}
    });
    ws.on('error',()=>{});
    ws.on('close',()=>{
      peers.delete(ws);
      if(!peers.size) rooms.delete(roomId);
      else for(const peer of peers) if(peer.readyState===WebSocket.OPEN)peer.send('{"type":"left"}');
    });
  });
  const heartbeat=setInterval(()=>{for(const ws of wss.clients){if(!ws.alive){ws.terminate();continue;}ws.alive=false;ws.ping();}},30000);
  heartbeat.unref();
  server.on('close',()=>{clearInterval(heartbeat);for(const ws of wss.clients)ws.terminate();wss.close();});
  return server;
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href) {
  createRelay().listen(Number(process.env.PORT ?? 8080),'0.0.0.0');
}
