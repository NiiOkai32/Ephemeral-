import {test} from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {WebSocket} from 'ws';
import {createRelay} from './server.mjs';

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
