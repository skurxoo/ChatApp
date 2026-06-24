const canvas=document.querySelector('#game'),ctx=canvas.getContext('2d');
const $=s=>document.querySelector(s),ui={message:$('#message'),score:$('#score'),hearts:$('#hearts'),room:$('#roomLabel'),level:$('#levelLabel'),status:$('#status'),map:$('#mapName'),desc:$('#mapDesc'),preview:$('#previewImage')};
const controls={moveX:0,jump:false,angle:-.35,sword:false,left:false,right:false};
const MAPS=[
 {name:'Factory Breakout',tag:'Break the central tower and fight across three floors',bg:'arena-factory.png',ground:670,tint:'#ffbd73',objects:['concrete','crate'],platforms:[[-836,150,-365],[245,150,-365],[-145,150,-145],[-65,150,-475]],spawns:[[-620,0],[-400,-365],[-120,0],[100,-145],[390,-365],[620,0]]},
 {name:'Rain Rooftops',tag:'Rainy roofs, vents, walls and dangerous jumps',bg:'arena-rooftop.png',ground:840,tint:'#8dc8ff',objects:['brick','vent'],platforms:[[-836,150,-460],[470,150,-465],[-320,150,-290],[220,150,-185],[-700,150,-190]],spawns:[[-650,0],[-520,-460],[-180,-290],[270,-185],[560,-465],[680,0]]},
 {name:'Jungle Ruins',tag:'Ancient platforms above dark jungle water',bg:'arena-jungle.png',ground:750,tint:'#9cde82',objects:['stone','pot'],platforms:[[-700,150,-480],[-90,150,-470],[280,150,-350],[-600,150,-130],[-170,150,-80],[340,150,-140]],spawns:[[-620,-130],[-500,-480],[-90,-80],[80,-470],[400,-350],[600,-140]]},
 {name:'Storm Harbor',tag:'Containers, cranes and breakable dock cargo',bg:'arena-harbor.png',ground:830,tint:'#77c9e8',objects:['crate','barrel'],platforms:[[-730,150,-480],[-330,150,-350],[200,150,-470],[300,150,-285],[-650,150,-250],[-80,150,-650]],spawns:[[-650,-250],[-530,-480],[-180,-350],[0,0],[350,-285],[520,-470]]},
 {name:'Broken Metro',tag:'Collapsed stations, tunnels and subway platforms',bg:'arena-metro.png',ground:850,tint:'#69d6b0',objects:['concrete','bench'],platforms:[[-836,150,-590],[120,150,-600],[-500,150,-340],[100,150,-350],[-760,150,-100],[350,150,-120]],spawns:[[-650,-100],[-520,-590],[-260,-340],[210,-350],[470,-600],[600,-120]]},
 {name:'Desert Fortress',tag:'Sandstone terraces, pots and crumbling walls',bg:'arena-desert.png',ground:815,tint:'#ffd08a',objects:['stone','pot'],platforms:[[-780,150,-405],[-470,150,-205],[-40,150,-350],[300,150,-500],[500,150,-210]],spawns:[[-650,0],[-520,-405],[-200,-205],[80,-350],[420,-500],[620,-210]]},
 {name:'Frozen Rail Yard',tag:'Icy train platforms and smashable frozen cargo',bg:'arena-snow.png',ground:740,tint:'#b8e5ff',objects:['ice','crate'],platforms:[[-836,150,-500],[350,150,-500],[-640,150,-240],[290,150,-230],[-210,150,-380]],spawns:[[-650,0],[-560,-500],[-280,-240],[0,-380],[400,-230],[580,-500]]}
];
const ASSET_VERSION='0.4.5';
const mapImages=MAPS.map(map=>{const image=new Image();image.src=`assets/${map.bg}?v=${ASSET_VERSION}`;return image});
const tigerImage=new Image();
tigerImage.src='assets/tiger_transparent.png?v='+ASSET_VERSION;
tigerImage.onload = function() {
  console.log('Tiger image loaded successfully');
};
tigerImage.onerror = function() {
  console.error('Failed to load tiger image');
};
let W=0,H=0,ground=0,last=0,running=false,mapIndex=0,level=1,kills=0,target=8,camera=0,spawnClock=0,sendClock=0,player,enemies=[],bits=[],objects=[],socket=null,room='',myName='',remote=new Map(),attackId=0,roundEndAt=0,roundWinner='',playerCount=1,roundNumber=1,tiger={x:0,y:0,vx:0,vy:0,face:1,grounded:true,health:100,hurt:0,attack:0,active:false,name:'Cat',attacking:false};
let audioContext=null;
function playSwordClang(){
 try{
  const AudioContext=window.AudioContext||window.webkitAudioContext;if(!AudioContext)return;
  const ac=audioContext||(audioContext=new AudioContext());if(ac.state==='suspended')ac.resume();
  const now=ac.currentTime,master=ac.createGain();master.gain.setValueAtTime(.0001,now);master.gain.exponentialRampToValueAtTime(.2,now+.004);master.gain.exponentialRampToValueAtTime(.0001,now+.24);master.connect(ac.destination);
  for(const [frequency,volume] of [[1180,.5],[1760,.28],[2630,.14]]){const oscillator=ac.createOscillator(),gain=ac.createGain();oscillator.type='triangle';oscillator.frequency.setValueAtTime(frequency*(.96+Math.random()*.08),now);oscillator.frequency.exponentialRampToValueAtTime(frequency*.82,now+.2);gain.gain.setValueAtTime(volume,now);gain.gain.exponentialRampToValueAtTime(.0001,now+.22);oscillator.connect(gain).connect(master);oscillator.start(now);oscillator.stop(now+.24)}
 }catch(_){/* Audio is optional on devices that block Web Audio. */}
}
function resize(){const d=Math.min(devicePixelRatio||1,2);W=innerWidth;H=innerHeight;ground=H*(MAPS[mapIndex].ground/941);canvas.width=W*d;canvas.height=H*d;canvas.style.width=W+'px';canvas.style.height=H+'px';ctx.setTransform(d,0,0,d,0,0)}
function selectMap(n){mapIndex=(n+7)%7;ui.map.textContent=MAPS[mapIndex].name;ui.desc.textContent=MAPS[mapIndex].tag;ui.preview.style.backgroundImage=`linear-gradient(#0002,#0009),url('assets/${MAPS[mapIndex].bg}?v=${ASSET_VERSION}')`;ui.preview.style.backgroundSize='cover';ui.preview.style.backgroundPosition='center';resize()}
function buildLevel(){const map=MAPS[mapIndex],spots=[[-520,0,64,74],[-260,0,50,66],[-20,0,66,88],[250,0,54,70],[520,0,62,82],[-390,map.platforms[0]?.[2]||0,48,60],[360,map.platforms[1]?.[2]||0,52,68]],kinds=['crate','barrel','explosive'];objects=spots.map((s,i)=>({id:`${mapIndex}-${level}-${i}`,x:s[0],y:s[1],w:s[2],h:s[3],hp:1+level,kind:kinds[(i+mapIndex)%kinds.length],dead:false,maxHp:1+level}))}
function reset(){const spots=MAPS[mapIndex].spawns,slot=myName?[...myName].reduce((a,c)=>a+c.charCodeAt(0),0)%spots.length:2,spawn=spots[slot];player={x:spawn[0],y:spawn[1],vx:0,vy:0,face:1,grounded:true,health:100,hurt:0,angle:-.35,sword:false,hit:new Set()};tiger={x:spawn[0]-100,y:spawn[1],vx:0,vy:0,face:1,grounded:true,health:100,hurt:0,attack:0,active:!room,name:'Cat',attacking:false};enemies=[];bits=[];remote.clear();camera=0;kills=0;target=6+level*2;spawnClock=.4;buildLevel();updateHud()}
function startGame(){reset();running=true;ui.message.classList.add('hidden')}
function spawnEnemy(){const spots=MAPS[mapIndex].spawns,spawn=spots[enemies.length%spots.length];enemies.push({x:spawn[0],y:spawn[1],vx:0,vy:0,face:spawn[0]<player.x?1:-1,health:35+level*18,hurt:0,attack:0,hit:-1,grounded:false,name:['Nora','Elias','Max','Luna','Otto','Ivy'][enemies.length%6]})}
function platforms(){return MAPS[mapIndex].platforms.map(([x,w,y])=>({x,w,y}))}
function update(dt){
 const move=((controls.right?1:0)-(controls.left?1:0))||controls.moveX;player.vx+=move*760*dt;player.vx*=Math.pow(.0015,dt);player.vx=Math.max(-185,Math.min(185,player.vx));if(move)player.face=Math.sign(move);
 if(controls.jump&&player.grounded){player.vy=-340;player.grounded=false;puff(player.x,player.y,7)}controls.jump=false;player.angle=controls.angle;player.sword=controls.sword;if(player.sword&&Math.abs(Math.cos(player.angle))>.18)player.face=Math.sign(Math.cos(player.angle));

   const ox=player.x,oy=player.y;player.vy+=800*dt;player.x+=player.vx*dt;player.y+=player.vy*dt;player.x=Math.max(-780,Math.min(780,player.x));player.grounded=false;
 let floor=0;for(const p of platforms())if(player.x>p.x-10&&player.x<p.x+p.w+10&&oy<=p.y&&player.y>=p.y&&player.vy>=0)floor=Math.min(floor,p.y);if(player.y>=floor){player.y=floor;player.vy=0;player.grounded=true}
 for(const o of objects)if(!o.dead&&player.y>o.y-o.h-12&&player.y<o.y+12&&Math.abs(player.x-o.x)<o.w/2+9){player.x=ox;player.vx*=-.18}
 player.hurt=Math.max(0,player.hurt-dt);camera=0;

  // Update tiger with physics-based following
  if(!room && tiger.active){
    // Aim to stay behind the player's facing direction
    const targetX = player.x - 100 * player.face;
    const dx = targetX - tiger.x;
    const absDx = Math.abs(dx);

    // Horizontal movement toward target
    if(absDx > 20) {
      tiger.vx += Math.sign(dx) * 400 * dt;
    } else {
      tiger.vx *= Math.pow(0.1, dt); // brake near target
    }
    tiger.vx = Math.max(-150, Math.min(150, tiger.vx));
    if(Math.abs(tiger.vx) > 5) tiger.face = Math.sign(tiger.vx);

    // Apply gravity
    tiger.vy += 800 * dt;
    const toY = tiger.y; // pre-gravity y for platform detection

    // Update position
    tiger.x += tiger.vx * dt;
    tiger.y += tiger.vy * dt;
    tiger.x = Math.max(-780, Math.min(780, tiger.x));

    // Platform collision
    tiger.grounded = false;
    let tf = 0;
    for(const p of platforms()) {
      if(tiger.x > p.x - 10 && tiger.x < p.x + p.w + 10 && toY <= p.y && tiger.y >= p.y && tiger.vy >= 0) {
        tf = Math.min(tf, p.y);
      }
    }
    if(tiger.y >= tf) {
      tiger.y = tf;
      tiger.vy = 0;
      tiger.grounded = true;
    }

    tiger.hurt = Math.max(0, tiger.hurt - dt);
  }

 spawnClock-=dt;if(!room&&spawnClock<=0&&enemies.length<Math.min(3+level,6)&&kills<target){spawnEnemy();spawnClock=2}
  for(const e of enemies){const dx=player.x-e.x,d=Math.abs(dx);e.face=Math.sign(dx)||e.face;e.hurt=Math.max(0,e.hurt-dt);e.attack=Math.max(0,e.attack-dt);if(e.hurt<=0&&d>28)e.vx+=Math.sign(dx)*300*dt;e.vx*=Math.pow(.015,dt);e.vx=Math.max(-78-level*5,Math.min(78+level*5,e.vx));e.x+=e.vx*dt;const eoy=e.y;e.vy+=800*dt;e.y+=e.vy*dt;e.grounded=false;let efloor=0;for(const p of platforms())if(e.x>p.x-10&&e.x<p.x+p.w+10&&eoy<=p.y&&e.y>=p.y&&e.vy>=0)efloor=Math.min(efloor,p.y);if(e.y>=efloor){e.y=efloor;e.vy=0;e.grounded=true}if(d<34&&e.attack<=0)e.attack=.8;if(e.attack>.38&&e.attack<.46&&player.hurt<=0){playSwordClang();damagePlayer(1+level/4,Math.sign(dx))}if(player.sword&&e.hit!==attackId&&swordHits(e.x,e.y)){e.hit=attackId;e.health-=34;e.hurt=.25;e.vx=player.face*150;e.vy=-120;playSwordClang();puff(e.x,e.y,8)}}
 if(player.sword){for(const o of objects)if(!o.dead&&swordHits(o.x,o.y-o.h*.45,26)){const key='o'+o.id;if(!player.hit.has(key)){player.hit.add(key);o.hp--;puff(o.x,o.y-o.h/2,18);if(o.hp<=0){o.dead=true;if(o.kind==='barrel'||o.kind==='explosive')explodeBarrel(o)}}}for(const [name,r]of remote)if(r.health>0&&swordHits(r.x,r.y,14)){const key='r'+name;if(!player.hit.has(key)){player.hit.add(key);playSwordClang();send(`HIT|${enc(name)}|18`)}}}else player.hit.clear();
 enemies=enemies.filter(e=>{if(e.health>0)return true;kills++;puff(e.x,e.y,18);return false});if(kills>=target&&enemies.length===0)completeLevel();
 for(const b of bits){b.x+=b.vx*dt;b.y+=b.vy*dt;b.vy+=(b.type==='fire'?-90:b.type==='smoke'?-35:180)*dt;b.life-=dt}bits=bits.filter(b=>b.life>0);sendClock-=dt;if(socket&&socket.readyState===1&&sendClock<=0){sendClock=.07;send(`STATE|${player.x}|${player.y}|${player.face}|${player.angle}|${player.health}|${mapIndex}|${level}`)}updateHud()
}
function completeLevel(){running=false;if(level<3){level++;ui.message.querySelector('strong').textContent='LEVEL CLEARED';ui.status.textContent=`${MAPS[mapIndex].name} level ${level} unlocked`;ui.message.classList.remove('hidden');setTimeout(startGame,1000)}else{ui.message.querySelector('strong').textContent='MAP COMPLETE';ui.status.textContent='All three levels cleared — choose the next map';ui.message.classList.remove('hidden');level=1}}
function damagePlayer(amount,dir){player.health=Math.max(0,player.health-amount);player.hurt=.7;player.vx=dir*170;player.vy=-120;player.grounded=false;puff(player.x,player.y,12);hitEffect(player.x,player.y);if(player.health<=0){running=false;ui.message.querySelector('strong').textContent='FALLEN';ui.status.textContent='Return to the fight';ui.message.classList.remove('hidden')}}
function swordHits(x,y,r=12){const ax=player.x,ay=player.y-30,bx=ax+Math.cos(player.angle)*88,by=ay+Math.sin(player.angle)*88,dx=bx-ax,dy=by-ay,t=Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)));return Math.hypot(x-(ax+dx*t),y-(ay+dy*t))<r}
function puff(x,y,n){for(let i=0;i<n;i++)bits.push({type:'dust',x,y,vx:(Math.random()-.5)*130,vy:-Math.random()*110,life:.3+Math.random()*.5,size:2+Math.random()*3,color:MAPS[mapIndex].tint})}
function hitEffect(x,y){for(let i=0;i<8;i++)bits.push({type:'hit',x,y,vx:(Math.random()-.5)*60,vy:-Math.random()*80,life:.3+Math.random()*.3,size:2+Math.random()*3,color:'#ff554f'})}
function explodeBarrel(o){for(let i=0;i<34;i++){const hot=i<22;bits.push({type:hot?'fire':'smoke',x:o.x+(Math.random()-.5)*o.w*.7,y:o.y-o.h*.45,vx:(Math.random()-.5)*240,vy:-60-Math.random()*210,life:hot?.35+Math.random()*.55:.7+Math.random(),size:hot?5+Math.random()*10:8+Math.random()*13,color:hot?(Math.random()>.45?'#ffb11f':'#ff4b19'):'#4a4c52'})}if(Math.abs(player.x-o.x)<115&&Math.abs(player.y-(o.y-o.h/2))<100)damagePlayer(1+level/4,Math.sign(player.x-o.x)||1);for(const e of enemies)if(Math.abs(e.x-o.x)<120&&Math.abs(e.y-(o.y-o.h/2))<110){e.health-=45;e.vx=Math.sign(e.x-o.x)*190}}
function draw(){ctx.fillStyle='#050607';ctx.fillRect(0,0,W,H);drawBackground();ctx.save();ctx.scale(W/1672,H/941);ctx.translate(836,MAPS[mapIndex].ground);drawWorld();ctx.restore();drawTitle()}
function drawBackground(){const image=mapImages[mapIndex];if(image.complete&&image.naturalWidth){ctx.drawImage(image,0,0,W,H)}else{ctx.fillStyle='#10141a';ctx.fillRect(0,0,W,H)}const g=ctx.createLinearGradient(0,0,0,H);g.addColorStop(0,'#0001');g.addColorStop(.7,'#0000');g.addColorStop(1,'#0008');ctx.fillStyle=g;ctx.fillRect(0,0,W,H)}
function drawWorld(){ctx.lineCap='round';ctx.strokeStyle='#ffffff88';ctx.fillStyle='#fff';ctx.lineWidth=2;for(const p of platforms()){ctx.fillStyle='#05060755';ctx.fillRect(p.x,p.y,p.w,8);ctx.strokeStyle='#ffffff66';ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(p.x+p.w,p.y);ctx.stroke()}for(const o of objects)if(!o.dead)drawObject(o);for(const b of bits){ctx.save();ctx.globalAlpha=Math.min(1,b.life*2);ctx.fillStyle=b.color;ctx.beginPath();ctx.arc(b.x,b.y,b.size*(b.type==='smoke'?(1.5-b.life*.25):1),0,Math.PI*2);ctx.fill();ctx.restore()}for(const e of enemies)drawFighter(e.x,e.y,e.face,e.attack,e.hurt,e.health,e.name,false);for(const [name,r]of remote)if(r.map===mapIndex&&r.level===level)drawFighter(r.x,r.y,r.face,0,0,r.health,name,false,r.angle);drawFighter(player.x,player.y,player.face,0,player.hurt,player.health,myName||'YOU',true,player.angle);if(!room && tiger.active)drawCat(tiger.x,tiger.y,tiger.face,tiger.attack,tiger.hurt,tiger.health,tiger.name)}
function drawObject(o){ctx.save();ctx.translate(o.x-o.w/2,o.y-o.h);if(o.kind==='crate')drawCrate(o);else drawBarrel(o);ctx.restore()}
function drawCrate(o){const g=ctx.createLinearGradient(0,0,o.w,o.h);g.addColorStop(0,'#a86b2e');g.addColorStop(.48,'#70401f');g.addColorStop(1,'#3d2215');ctx.fillStyle=g;ctx.strokeStyle='#d29a54';ctx.lineWidth=2;ctx.fillRect(0,0,o.w,o.h);ctx.strokeRect(1,1,o.w-2,o.h-2);ctx.fillStyle='#4b2a17';ctx.fillRect(5,0,7,o.h);ctx.fillRect(o.w-12,0,7,o.h);ctx.fillRect(0,7,o.w,7);ctx.fillRect(0,o.h-14,o.w,7);ctx.strokeStyle='#d09a58';ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(10,12);ctx.lineTo(o.w-10,o.h-12);ctx.moveTo(o.w-10,12);ctx.lineTo(10,o.h-12);ctx.stroke();drawDamage(o,'#1b0e09')}
function drawBarrel(o){const explosive=o.kind==='explosive',g=ctx.createLinearGradient(0,0,o.w,0);g.addColorStop(0,'#20262a');g.addColorStop(.25,explosive?'#a12619':'#52616a');g.addColorStop(.55,explosive?'#db3d20':'#778892');g.addColorStop(.82,explosive?'#74170f':'#344047');g.addColorStop(1,'#14181b');ctx.fillStyle=g;ctx.strokeStyle='#aeb7bb';ctx.lineWidth=2;ctx.beginPath();ctx.ellipse(o.w/2,6,o.w/2-3,6,0,0,Math.PI*2);ctx.fill();ctx.stroke();ctx.fillRect(3,6,o.w-6,o.h-12);ctx.strokeRect(3,6,o.w-6,o.h-12);ctx.beginPath();ctx.ellipse(o.w/2,o.h-6,o.w/2-3,6,0,0,Math.PI*2);ctx.fill();ctx.stroke();ctx.fillStyle='#15191b';ctx.fillRect(2,o.h*.25,o.w-4,6);ctx.fillRect(2,o.h*.7,o.w-4,6);ctx.fillStyle='#d8a22b';ctx.beginPath();ctx.moveTo(o.w*.5,o.h*.31);ctx.lineTo(o.w*.7,o.h*.56);ctx.lineTo(o.w*.55,o.h*.56);ctx.lineTo(o.w*.66,o.h*.68);ctx.lineTo(o.w*.34,o.h*.51);ctx.lineTo(o.w*.49,o.h*.51);ctx.closePath();if(explosive)ctx.fill();drawDamage(o,'#080a0b')}
function drawDamage(o,color){const lost=o.maxHp-o.hp;if(lost<=0)return;ctx.strokeStyle=color;ctx.lineWidth=2;for(let i=0;i<lost+1;i++){const x=o.w*(.28+i*.19);ctx.beginPath();ctx.moveTo(x,o.h*.16);ctx.lineTo(x-5,o.h*.38);ctx.lineTo(x+4,o.h*.55);ctx.lineTo(x-3,o.h*.78);ctx.stroke()}}
function drawFighter(x,y,face,attack,hurt,health,name,hero,angleOverride){ctx.save();ctx.translate(x,y);ctx.scale((face||1)*1.65,1.65);ctx.globalAlpha=hurt>0&&Math.floor(hurt*20)%2 ? .25 : 1;ctx.strokeStyle=hero?'#fff':MAPS[mapIndex].tint;ctx.lineWidth=hero?2.5:2;ctx.beginPath();ctx.arc(0,-34,5,0,Math.PI*2);ctx.moveTo(0,-29);ctx.lineTo(0,-13);const stride=Math.sin(performance.now()*.012+x)*4;ctx.moveTo(0,-13);ctx.lineTo(-7-stride,0);ctx.moveTo(0,-13);ctx.lineTo(7+stride,0);ctx.moveTo(0,-25);ctx.lineTo(-8,-18);ctx.stroke();const a=angleOverride??(attack>0?-.9+Math.sin((1-attack/.8)*Math.PI)*2:-.35),localA=face<0?Math.PI-a:a;ctx.save();ctx.translate(7,-20);ctx.rotate(localA);const bladeL=hero?68:54,bladeW=hero?10:8;ctx.fillStyle=hero?'#dff6ff':MAPS[mapIndex].tint;ctx.strokeStyle='#fff';ctx.lineWidth=1.4;ctx.beginPath();ctx.moveTo(3,-bladeW/2);ctx.lineTo(bladeL-10,-bladeW/2);ctx.lineTo(bladeL,0);ctx.lineTo(bladeL-10,bladeW/2);ctx.lineTo(3,bladeW/2);ctx.closePath();ctx.fill();ctx.stroke();// Add glow effect to sword blade for better visual impact
 ctx.shadowColor=hero?'#ffffff':MAPS[mapIndex].tint;
 ctx.shadowBlur=5;
 ctx.beginPath();
 ctx.moveTo(3,-bladeW/2);
 ctx.lineTo(bladeL-10,-bladeW/2);
 ctx.lineTo(bladeL,0);
 ctx.lineTo(bladeL-10,bladeW/2);
 ctx.lineTo(3,bladeW/2);
 ctx.closePath();
 ctx.fill();
 ctx.shadowBlur=0;
 ctx.strokeStyle='rgba(25,35,45,.7)';
 ctx.lineWidth=1.2;
 ctx.beginPath();
 ctx.moveTo(8,0);
 ctx.lineTo(bladeL-9,0);
 ctx.stroke();
 ctx.fillStyle='#c49a42';
 ctx.strokeStyle='#fff';
 ctx.fillRect(-2,-9,5,18);
 ctx.strokeRect(-2,-9,5,18);
 ctx.fillStyle='#33251c';
 ctx.fillRect(-11,-2.5,9,5);
 ctx.beginPath();
 ctx.arc(-12,0,3.5,0,Math.PI*2);
 ctx.fill();
 ctx.stroke();
 ctx.restore();
 ctx.scale(face||1,1);
 ctx.textAlign='center';
 ctx.fillStyle='#000b';
 ctx.fillRect(-28,-58,56,14);
 ctx.fillStyle='#fff';
 ctx.font='bold 9px monospace';
 ctx.fillText(name.slice(0,10),0,-48);
 ctx.fillStyle='#430b0b';
 ctx.fillRect(-25,-44,50,5);
 ctx.fillStyle=health>35?'#75e07a':'#ff554f';
 ctx.fillRect(-25,-44,50*Math.max(0,health)/100,5);
 ctx.restore();
}
function drawTitle(){ctx.fillStyle='#0009';ctx.fillRect(12,H-30,240,20);ctx.fillStyle='#fff';ctx.font='11px monospace';ctx.fillText(`${MAPS[mapIndex].name} · level ${level}/3`,20,H-16)}

function drawCat(x,y,face,attack,hurt,health,name){
   if(!tigerImage.complete) return;

   ctx.save();
   ctx.translate(x,y);
   ctx.scale((face||1)*1.5,1.5);
   ctx.globalAlpha=hurt>0&&Math.floor(hurt*20)%2 ? .25 : 1;

   ctx.drawImage(tigerImage,-15,-30,30,30);
   ctx.restore();
 }
function updateHud(){ui.score.textContent=room?`ROUND ${roundNumber}`:`${kills} / ${target}`;ui.hearts.textContent=`${Math.ceil((player?.health||100))}%`;ui.level.textContent=room?'LAST STANDING':`LEVEL ${level}`;ui.room.textContent=room?`ROOM ${room} · ${playerCount}/6`:'SOLO'}
function updateRoundInfo(){if(!roundEndAt)return;const seconds=Math.max(0,Math.ceil((roundEndAt-Date.now())/1000));ui.round.querySelector('span').textContent=`Next round starts in ${seconds}`}
function frame(t){const dt=Math.min(.033,(t-last)/1000||0);last=t;if(running)update(dt);updateRoundInfo();draw();requestAnimationFrame(frame)}
function bindStick(el){const kind=el.dataset.stick,knob=el.querySelector('i');let id=null;const move=e=>{if(e.pointerId!==id)return;const r=el.getBoundingClientRect(),rad=r.width*.34;let x=e.clientX-r.left-r.width/2,y=e.clientY-r.top-r.height/2,l=Math.hypot(x,y),s=l>rad?rad/l:1;x*=s;y*=s;knob.style.transform=`translate(calc(-50% + ${x}px),calc(-50% + ${y}px))`;if(kind==='move'){controls.moveX=x/rad;if(y/rad<-.48)controls.jump=true}else{controls.angle=Math.atan2(y,x);controls.sword=l>9;if(controls.sword)attackId++}};const up=e=>{if(e.pointerId!==id)return;id=null;knob.style.transform='translate(-50%,-50%)';if(kind==='move')controls.moveX=0;else controls.sword=false};el.onpointerdown=e=>{e.preventDefault();id=e.pointerId;el.setPointerCapture(id);move(e)};el.onpointermove=move;el.onpointerup=up;el.onpointercancel=up}
function enc(s){return btoa(unescape(encodeURIComponent(s))).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_')}function dec(s){try{return decodeURIComponent(escape(atob(s.replace(/-/g,'+').replace(/_/g,'/'))))}catch{return s}}
function send(s){if(socket?.readyState===1)socket.send(s)}
 function connect(command){if(socket)socket.close();myName=$('#playerName').value.trim().slice(0,24)||'Swordsman';localStorage.swordsName=myName;socket=new WebSocket('wss://somechatapp.ddns.net/swords');ui.status.textContent='Connecting…';socket.onopen=()=>send(`${command}|${enc(myName)}${command==='JOIN'?'|'+$('#roomCode').value.toUpperCase():''}`);socket.onmessage=e=>{const p=e.data.split('|');if(p[0]==='ROOM'){room=p[1];ui.status.textContent=`Room ${room} · waiting for fighters`;startGame()}else if(p[0]==='PLAYERS'){playerCount=p.slice(1).filter(Boolean).length;ui.status.textContent=playerCount>1?`${playerCount}/6 players · last fighter standing wins`:`${playerCount}/6 player · waiting for another fighter`;updateHud()}else if(p[0]==='STATE'){const n=dec(p[1]);if(n!==myName)remote.set(n,{x:+p[2],y:+p[3],face:+p[4],angle:+p[5],health:+p[6],map:+p[7],level:+p[8]})}else if(p[0]==='DAMAGE'){playSwordClang();player.health=Math.max(0,+p[1]);player.hurt=.5;if(player.health<=0){running=false;controls.moveX=0;controls.sword=false}}else if(p[0]==='ROUND'){roundWinner=dec(p[1]);roundEndAt=Date.now()+(+p[2]||5)*1000;running=false;ui.round.querySelector('strong').textContent=roundWinner?`${roundWinner} WINS THE ROUND`:'ROUND DRAW';ui.round.classList.remove('hidden')}else if(p[0]==='RESET'){roundNumber=+p[1]||roundNumber+1;roundEndAt=0;ui.round.classList.add('hidden');reset();running=true}else if(p[0]==='LEFT')remote.delete(dec(p[1]));else if(p[0]==='ERROR')ui.status.textContent=p.slice(1).join(' ')};socket.onerror=()=>ui.status.textContent='Could not connect to multiplayer server';socket.onclose=()=>{if(room)ui.status.textContent='Multiplayer disconnected';room='';updateHud()}}
document.querySelectorAll('[data-stick]').forEach(bindStick);$('#mapPrev').onclick=()=>selectMap(mapIndex-1);$('#mapNext').onclick=()=>selectMap(mapIndex+1);$('#solo').onclick=()=>{socket?.close();room='';myName=$('#playerName').value.trim()||'YOU';startGame()};$('#quick').onclick=()=>connect('QUICK');$('#create').onclick=()=>connect('CREATE');$('#join').onclick=()=>connect('JOIN');$('#roomCode').oninput=e=>e.target.value=e.target.value.replace(/[^a-z]/gi,'').toUpperCase().slice(0,3);const loginName=new URLSearchParams(location.search).get('player')?.trim().slice(0,24);$('#playerName').value=loginName||localStorage.swordsName||'Swordsman';if(loginName){$('#playerName').readOnly=true;$('#playerName').title='ChatApp login name';}addEventListener('keydown',e=>{if(e.code==='KeyA')controls.left=true;if(e.code==='KeyD')controls.right=true;if(e.code==='KeyW')controls.jump=true;if(e.code==='Space'){controls.sword=true;attackId++}});addEventListener('keyup',e=>{if(e.code==='KeyA')controls.left=false;if(e.code==='KeyD')controls.right=false;if(e.code==='Space')controls.sword=false});addEventListener('resize',resize);selectMap(0);resize();reset();requestAnimationFrame(frame);
