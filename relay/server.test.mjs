import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {WebSocket} from 'ws';
import {createRelay,turnConfigFromEnv} from './server.mjs';

test('relay isolates rooms, forwards ciphertext, rejects third peer and reports disconnect',async()=>{
 const server=createRelay();server.listen(0,'127.0.0.1');await once(server,'listening');
 const base=`ws://127.0.0.1:${server.address().port}/room/`;
 const clients=[];
 const connect=async room=>{const ws=new WebSocket(base+room);clients.push(ws);await once(ws,'open');return ws;};
 try {
  const a=await connect('a'.repeat(64));
  const aReady=once(a,'message');
  const bSocket=new WebSocket(base+'a'.repeat(64));clients.push(bSocket);
  const bReady=once(bSocket,'message');await once(bSocket,'open');
  assert.equal(JSON.parse((await aReady)[0]).type,'peer');
  assert.equal(JSON.parse((await bReady)[0]).type,'peer');
  const b=bSocket;const c=await connect('b'.repeat(64));let leaked=false;c.on('message',()=>{leaked=true;});
  const received=once(b,'message');a.send(JSON.stringify({type:'data',data:'encrypted-payload'}));
  assert.equal(JSON.parse((await received)[0]).data,'encrypted-payload');
  await new Promise(resolve=>setTimeout(resolve,50));assert.equal(leaked,false);
  const extra=new WebSocket(base+'a'.repeat(64));clients.push(extra);
  assert.equal((await once(extra,'close'))[0],1008);
  const left=once(a,'message');b.close();assert.equal(JSON.parse((await left)[0]).type,'left');
 } finally {for(const ws of clients)ws.terminate();await new Promise(resolve=>server.close(resolve));}
});
test('rejects invalid payload types',async()=>{
 const server=createRelay();server.listen(0,'127.0.0.1');await once(server,'listening');
 const ws=new WebSocket(`ws://127.0.0.1:${server.address().port}/room/${'c'.repeat(64)}`);
 await once(ws,'open');const closed=once(ws,'close');ws.send('{"type":"plaintext"}');assert.equal((await closed)[0],1008);
 await new Promise(resolve=>server.close(resolve));
});

test('calling configuration is supplied only to paired connections without embedding account API keys',async()=>{
 const iceServers=[{urls:['turns:turn.example.com:443?transport=tcp'],username:'test-user',credential:'test-password'}];
 const server=createRelay({iceServers});server.listen(0,'127.0.0.1');await once(server,'listening');
 const address=`ws://127.0.0.1:${server.address().port}/room/${'d'.repeat(64)}`;
 const a=new WebSocket(address);let b;
 try {
  await once(a,'open');const rejected=once(a,'message');a.send('{"type":"ice"}');
  assert.equal(JSON.parse((await rejected)[0]).error,'Join a paired conversation first');
  const aReady=once(a,'message');b=new WebSocket(address);const bReady=once(b,'message');
  await once(b,'open');await aReady;await bReady;
  const config=once(a,'message');a.send('{"type":"ice"}');
  const reply=JSON.parse((await config)[0]);assert.deepEqual(reply.iceServers,iceServers);
  assert.equal(JSON.stringify(reply).includes('apiKey'),false);
 }finally{a.terminate();b?.terminate();await new Promise(resolve=>server.close(resolve));}
});
test('TURN environment validation permits missing setup and rejects invalid URLs',()=>{
 assert.equal(turnConfigFromEnv({}),null);
 assert.throws(()=>turnConfigFromEnv({TURN_URLS:'https://wrong.example',TURN_USERNAME:'u',TURN_PASSWORD:'p'}));
 assert.deepEqual(turnConfigFromEnv({TURN_URLS:'turn:turn.example:80,turns:turn.example:443',TURN_USERNAME:'u',TURN_PASSWORD:'p'}),[
  {urls:['turn:turn.example:80','turns:turn.example:443'],username:'u',credential:'p'}
 ]);
});
