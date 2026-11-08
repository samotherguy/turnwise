// Stray-style pass: real cat proportions in profile, neon rim light, natural motion. Same 168 x 60 box.
const NIGHT='#0E0F14', ORANGE='#FF8A3D', CYAN='#3FD6E8', PINK='#FF5FA8';
const BLACK='#121216', BLACK_LIGHT='#2C2C36', BLACK_EYE='#D4E65A';
const TABBY='#5A4A3A', TABBY_LIGHT='#8C7A62', TABBY_DARK='#2E251C', STRIPE='#1F1813', TABBY_EYE='#9FD07E', MUZZLE='#D9CDBA', NOSE='#C77F7A';
const INK='#0A0A0C', RAISED='#282828', MIST='#B3B3B3', WHITE='#FFFFFF', FEATHER='#A9DDE3', FEATHER_DARK='#6FB3BF';

function popIn(x){const c1=1.70158,c3=c1+1,y=x-1;return 1+c3*y*y*y+c1*y*y}
function envelope(p,a,b,c,d){return (p<a||p>d)?0:(p<b)?popIn((p-a)/(b-a)):(p<c)?1:1-(p-c)/(d-c)}
function blink(t,off){const ph=((t+off)%4600)/4600;return ph<0.92?1:1-0.9*Math.min(1,Math.max(0,1-abs(ph-0.96)/0.04))}
function clamp(x,a,b){return Math.min(b,Math.max(a,x))}

// rim light: cyan from the left of the picture, orange neon from the right
function rim(x0,x1,k){return {grad:(()=>{const g=ctx.createLinearGradient(x0,0,x1,0);g.addColorStop(0,alpha(CYAN,0.85*k));g.addColorStop(0.45,alpha(CYAN,0));g.addColorStop(0.6,alpha(ORANGE,0));g.addColorStop(1,alpha(ORANGE,0.95*k));return g})()}}

function drawDuo(m,t){
  const cyc=(t%5000)/5000;
  const talk1=m==='CHAT'&&cyc>=0.06&&cyc<=0.48, talk2=m==='CHAT'&&cyc>=0.56&&cyc<=0.98;
  const dim=m==='SLEEP'?0.45:1;
  ambience(t,dim);
  if(m==='LISTEN') soundBars(t);
  const breathe=1+0.015*s(t/900);
  withTransform(x=>{x.scale(1,breathe,Offset(30,54))},()=>blackCat(m,t,talk1));
  withTransform(x=>{x.scale(1,1+0.012*s(t/1100+1),Offset(130,54))},()=>tabby(m,t,talk2));
  ledge(dim);
  if(m==='CHAT'){bubble(50,10,envelope(cyc,0.06,0.10,0.44,0.48),Offset(50,22),true,t);bubble(72,8,envelope(cyc,0.56,0.60,0.94,0.98),Offset(108,22),false,t+500)}
  else if(m==='THINK'){bubble(50,10,0.85+0.05*s(t/400),Offset(50,22),true,t);bubble(72,8,0.85+0.05*s(t/400+2),Offset(108,22),false,t+330)}
  else if(m==='SLEEP') snores(t);
  else if(m==='CELEBRATE'){feather(t)}
}
function ambience(t,dim){   // neon haze from both sides, a little flicker in the orange sign
  const fl=0.85+0.15*s(t/90)*s(t/230);
  drawRect(radialGradient([alpha(CYAN,0.22*dim),alpha(CYAN,0)],Offset(-6,8),70),Offset(0,0),Size(168,60));
  drawRect(radialGradient([alpha(ORANGE,0.28*dim*fl),alpha(ORANGE,0)],Offset(176,4),80),Offset(0,0),Size(168,60));
}
function ledge(dim){   // the rooftop edge they sit on, lit by the sign
  drawRect(alpha('#000000',0.5),Offset(0,54),Size(168,6));
  drawLine({grad:(()=>{const g=ctx.createLinearGradient(0,0,168,0);g.addColorStop(0,alpha(CYAN,0.5*dim));g.addColorStop(0.5,alpha(WHITE,0.08));g.addColorStop(1,alpha(ORANGE,0.7*dim));return g})()},Offset(0,54.3),Offset(168,54.3),0.8,'butt');
}

// ---------- Frito, the black cat, in profile facing right ----------
function blackCat(m,t,talking){
  if(m==='SLEEP'){curled(Offset(28,47),14,BLACK,BLACK_LIGHT,0.9,t);return}
  if(m==='LISTEN'){blackStanding(t);return}
  if(m==='CELEBRATE'){blackBow(t);return}
  const r=rim(8,52,1);
  // tail wraps round the front, tip lifting now and then
  const tail=Path();tail.moveTo(15,50);tail.quadraticTo(9,56,24,56.2);tail.quadraticTo(36,56.5,44,53-2.5*max(0,s(t/700)));
  drawPath(tail,BLACK,Stroke(3,'round'));drawPath(tail,r,Stroke(1,'round'));
  // sitting: straight chest and front legs, back sloping down to a round haunch
  const body=Path();body.moveTo(39,54);body.lineTo(39,42);body.cubicTo(39,36,38,31,38.5,27);body.cubicTo(36,25,32,26,30,28.5);body.cubicTo(25,32,19,37,15.5,45);body.cubicTo(13,50,13.5,53.5,16,54);body.close();
  drawPath(body,BLACK);drawPath(body,r,Stroke(1.1));
  drawLine(alpha(BLACK_LIGHT,0.5),Offset(35,44),Offset(35,53),0.8,'round');                         // the second front leg
  const thigh=Path();thigh.moveTo(17,45);thigh.quadraticTo(22,46,26,53.5);drawPath(thigh,alpha(BLACK_LIGHT,0.45),Stroke(0.8,'round'));
  drawOval(alpha(BLACK_LIGHT,0.5),Offset(22,52.5),Size(8,2.2),Stroke(0.7));                        // hind paw
  const tilt=m==='THINK'?-18:(talking?-5*abs(s(t/150)):0);
  rotate(tilt,Offset(37,30),()=>head(Offset(42,23.5),t,talking,m,BLACK,BLACK_EYE,r,0));
}
function blackStanding(t){   // on all fours, head toward the sound, tail up, one ear flicking
  const r=rim(6,58,1);
  const tail=Path();tail.moveTo(14,38);tail.quadraticTo(5,34+2*s(t/600),8,22+3*s(t/900));
  drawPath(tail,BLACK,Stroke(2.8,'round'));drawPath(tail,r,Stroke(1,'round'));
  const body=Path();body.moveTo(14,37);body.cubicTo(20,31.5,34,31,44,33);body.cubicTo(47.5,34,49,36.5,48.5,39);body.cubicTo(48,43,45,45.5,40,46);body.cubicTo(32,47,22,47,17,45);body.cubicTo(13,43.5,12,40,14,37);body.close();
  drawPath(body,BLACK);drawPath(body,r,Stroke(1.1));
  const leg=(a,b,c)=>{const p=Path();p.moveTo(a.x,a.y);p.lineTo(b.x,b.y);p.lineTo(c.x,c.y);drawPath(p,BLACK,Stroke(3,'round'));};
  leg(Offset(19,43),Offset(17,49),Offset(18,53.5)); leg(Offset(24,44),Offset(23,49),Offset(24.5,53.5));
  leg(Offset(41,44),Offset(41,49),Offset(41.5,53.5)); leg(Offset(46,43),Offset(46.5,49),Offset(47.5,53.5));
  drawLine(r,Offset(48.2,44),Offset(48.8,53),0.9,'round');
  rotate(-8,Offset(48,37),()=>head(Offset(53,30),t,false,'LISTEN',BLACK,BLACK_EYE,r,1));
}
function blackBow(t){   // the play bow: rump up, chest down, front paws reaching for the feather, one swatting
  const r=rim(4,66,1);
  const rock=1.5*s(t/700);
  const tail=Path();tail.moveTo(12,36);tail.quadraticTo(4,30,7,20+2*s(t/300));
  drawPath(tail,BLACK,Stroke(2.8,'round'));drawPath(tail,r,Stroke(1,'round'));
  const body=Path();body.moveTo(11,46);body.cubicTo(8,38,12,30,20,29);body.cubicTo(28,28,36,36,42,43+rock);body.cubicTo(45,46+rock,46,49,43,50);body.cubicTo(36,51,26,50,20,49);body.cubicTo(15,48.5,12,48,11,46);body.close();
  drawPath(body,BLACK);drawPath(body,r,Stroke(1.1));
  drawLine(BLACK,Offset(14,47),Offset(12,53.5),3,'round');drawLine(BLACK,Offset(21,49),Offset(20,53.5),3,'round');
  const sw=max(0,s(t/260));                                                                      // one paw up at the feather
  drawLine(BLACK,Offset(40,48+rock),Offset(58,53.5),3.2,'round');
  const paw=Offset(52+8*sw,52-14*sw);drawLine(BLACK,Offset(43,47+rock),paw,3.2,'round');drawCircle(BLACK,2.2,paw);drawCircle(r,2.2,paw,Stroke(0.9));
  drawLine(r,Offset(42,50+rock),Offset(58,54),0.9,'round');
  rotate(-28,Offset(44,45+rock),()=>head(Offset(49,40+rock),t,false,'CELEBRATE',BLACK,BLACK_EYE,r,1));
}

// ---------- the tabby, lying in profile facing left ----------
function tabby(m,t,talking){
  if(m==='SLEEP'){curled(Offset(132,46),20,TABBY,TABBY_LIGHT,1,t,true);return}
  const r=rim(96,166,1);
  const fur=radialGradient([TABBY_LIGHT,TABBY,TABBY_DARK],Offset(120,40),50);
  const tw=s(t/1500)*4;
  const tail=Path();tail.moveTo(156,48);tail.quadraticTo(166,50,164+tw*0.3,42+tw);
  drawPath(tail,TABBY_DARK,Stroke(4.4,'round'));drawPath(tail,TABBY,Stroke(2.6,'round'));drawPath(tail,r,Stroke(1,'round'));
  const up=m==='LISTEN'||m==='CELEBRATE'?4:0;
  // the long low loaf
  const body=Path();body.moveTo(104,54);body.cubicTo(100,44,108,34-up,124,33-up);body.cubicTo(140,32-up,158,36,160,46);body.cubicTo(161,52,156,54,150,54);body.close();
  drawPath(body,fur);
  for(let i=0;i<7;i++){const x=116+i*6.5;const p=Path();p.moveTo(x,35-up+i*0.4);p.quadraticTo(x+2,41,x-1,46);drawPath(p,alpha(STRIPE,0.7),Stroke(2,'round'))}
  drawOval(alpha(TABBY_LIGHT,0.35),Offset(120,46),Size(30,8));
  drawPath(body,r,Stroke(1.1));
  for(const x of [110,118]) drawOval(alpha(TABBY_LIGHT,0.7),Offset(x-5,51),Size(10,3.5));
  const tilt=m==='THINK'?6:(talking?-4*abs(s(t/160)):0);
  rotate(tilt,Offset(112,42),()=>head(Offset(106,36-up),t,talking,m,fur,TABBY_EYE,r,2));
}

// ---------- shared ----------
// facing: 0 = black cat looking right; 1 = black cat looking right (standing, same); 2 = tabby looking left
function head(c,t,talking,m,fill,eye,r,kind){
  const dir=kind===2?-1:1, big=kind===2;
  const w=big?20:14, h=big?15:11;
  // ears
  const ear=(ox,tilt)=>{rotate(dir*tilt,Offset(c.x+dir*ox,c.y-h*0.45),()=>{const p=Path();p.moveTo(c.x+dir*(ox-3),c.y-h*0.4);p.lineTo(c.x+dir*(ox+0.5),c.y-h*0.45-(big?7.5:8)-(m==='LISTEN'?2:0));p.lineTo(c.x+dir*(ox+4),c.y-h*0.3);p.close();drawPath(p,fill);drawPath(p,r,Stroke(0.9))})};
  const flick=m==='LISTEN'?10*max(0,s(t/260))*max(0,s(t/1900)):0;
  ear(-w*0.25,m==='THINK'?16:flick); ear(w*0.12,0);
  // skull + muzzle
  drawOval(fill,Offset(c.x-w/2,c.y-h/2),Size(w,h));
  drawOval(fill,Offset(c.x+dir*w*0.22-(big?6:4.5),c.y-1),Size(big?12:9,big?8:6.5));                 // muzzle
  if(big){drawOval(MUZZLE,Offset(c.x+dir*w*0.3-4.5,c.y+0.5),Size(9,6))}
  drawOval(fill,Offset(c.x-w/2,c.y-h/2),Size(w,h),r?Stroke(1.0):undefined);
  if(big){for(const dx of [-3,0,3])drawLine(alpha(STRIPE,0.7),Offset(c.x+dx,c.y-h*0.5+1),Offset(c.x+dx*0.8,c.y-h*0.2),1.2,'round')}
  // nose + mouth
  nose(Offset(c.x+dir*(w*0.5+(big?1:2)),c.y+1),big?NOSE:BLACK_LIGHT,big?0.9:0.6);
  if(talking){const o=1+1.8*abs(s(t/110));drawOval(INK,Offset(c.x+dir*(w*0.5-1)-1.2,c.y+2.6),Size(2.4,o))}
  // the eye: one visible, glowing in the dark
  const ex=c.x+dir*w*0.18, ey=c.y-h*0.1, open=blink(t,kind*800);
  const er=big?3.1:2.3, eh=er*0.75*open;
  const lookUp=m==='THINK'?-0.8:0, lookFwd=dir*(m==='LISTEN'||m==='CELEBRATE'?0.9:0.4);
  drawCircle(radialGradient([alpha(eye,0.35),alpha(eye,0)],Offset(ex,ey),er*2.2),er*2.2,Offset(ex,ey));
  const al=Path();al.moveTo(ex-er,ey);al.quadraticTo(ex,ey-eh*2,ex+er,ey);al.quadraticTo(ex,ey+eh*2,ex-er,ey);al.close();drawPath(al,eye);
  if(open>0.3){const pw=(m==='LISTEN'||m==='CELEBRATE')?er*0.9:er*0.35;drawOval(INK,Offset(ex+lookFwd-pw/2,ey+lookUp-eh*0.9),Size(pw,eh*1.8));drawCircle(alpha(WHITE,0.9),er*0.22,Offset(ex+lookFwd-er*0.2,ey+lookUp-eh*0.4))}
  // whiskers
  for(const dy of [-1.5,0,1.5])drawLine(alpha(WHITE,big?0.45:0.3),Offset(c.x+dir*(w*0.5),c.y+1.5+dy*0.3),Offset(c.x+dir*(w*0.5+(big?14:11)),c.y+dy*2.2),0.5,'round');
}
function curled(c,rad,fill,light,k,t,striped){   // asleep: a ring with the head tucked, breathing
  const r=rim(c.x-rad-4,c.x+rad+4,0.6), br=0.4*s(t/1300);
  const body=striped?radialGradient([light,fill,TABBY_DARK],Offset(c.x-rad*0.3,c.y-rad*0.3),rad*1.6):fill;
  drawOval(body,Offset(c.x-rad*1.2,c.y-rad*0.55-br),Size(rad*2.4,rad*1.1+br));
  if(striped)for(let i=0;i<6;i++){const x=c.x-rad*0.9+i*rad*0.36;const p=Path();p.moveTo(x,c.y-rad*0.5);p.quadraticTo(x+2,c.y-rad*0.1,x,c.y+rad*0.3);drawPath(p,alpha(STRIPE,0.6),Stroke(1.6,'round'))}
  drawOval(body,Offset(c.x-rad*1.2,c.y-rad*0.55-br),Size(rad*2.4,rad*1.1+br),Stroke(1));
  drawOval(body,Offset(c.x-rad*1.2,c.y-rad*0.55-br),Size(rad*2.4,rad*1.1+br),r?Stroke(1):undefined);
  // head tucked at the front, ears just showing, eye shut
  const hx=c.x-rad*0.7, hy=c.y+rad*0.1;
  const p=Path();p.moveTo(hx-2,hy-4);p.lineTo(hx-1,hy-9);p.lineTo(hx+3,hy-4.5);p.close();drawPath(p,fill);drawPath(p,r,Stroke(0.8));
  drawOval(fill,Offset(hx-6,hy-5),Size(12,9));drawOval(r,Offset(hx-6,hy-5),Size(12,9),Stroke(0.9));
  drawArc(light,20,140,false,Offset(hx-3.5,hy-2.5),Size(5,3),Stroke(1,'round'));
  // tail along the belly
  const tail=Path();tail.moveTo(c.x+rad*1.1,c.y+rad*0.3);tail.quadraticTo(c.x,c.y+rad*0.75,hx-5,c.y+rad*0.45);drawPath(tail,fill,Stroke(striped?3.6:2.8,'round'));drawPath(tail,r,Stroke(0.9,'round'));
}
function nose(c,color,k){const p=Path();p.moveTo(c.x-1.6*k,c.y-1*k);p.lineTo(c.x+1.2*k,c.y-0.6*k);p.lineTo(c.x-0.6*k,c.y+1.2*k);p.close();drawPath(p,color)}
function bubble(x,y,k,pivot,left,t){if(k<=0.01)return;
  scale(k,k,pivot,()=>{const tail=Path();if(left){tail.moveTo(x+6,y+16);tail.lineTo(x+1,y+24);tail.lineTo(x+12,y+19)}else{tail.moveTo(x+26,y+16);tail.lineTo(x+31,y+24);tail.lineTo(x+20,y+19)}tail.close();
    drawRoundRect(alpha('#000000',0.3),Offset(x+1,y+2),Size(32,18),9);drawPath(tail,RAISED);drawRoundRect(RAISED,Offset(x,y),Size(32,18),9);drawRoundRect(alpha(WHITE,0.12),Offset(x,y),Size(32,18),9,Stroke(0.8));
    for(let i=0;i<3;i++){const dy=-2*max(0,s(t/1000*2*PI-i*0.9));drawCircle(left?CYAN:ORANGE,2,Offset(x+9+i*7,y+9+dy))}})}
function soundBars(t){for(let i=0;i<7;i++){const h=3+9*abs(s(t/(170+i*23)+i));drawRoundRect(alpha(WHITE,0.5),Offset(68+i*5,54-h),Size(2.6,h),1.3)}}
function snores(t){for(let k=0;k<3;k++){const p=((t/2600)+k/3)%1,size=3+3.5*p,x=118+14*p,y=34-20*p;const z=Path();z.moveTo(x,y);z.lineTo(x+size,y);z.lineTo(x,y+size);z.lineTo(x+size,y+size);drawPath(z,alpha(MIST,1-p),Stroke(1.2,'round'))}}
function feather(t){const a=24*s(t/520),px=84,py=-4;
  rotate(a,Offset(px,py),()=>{drawLine(alpha(WHITE,0.4),Offset(px,py),Offset(px,30),0.7,'round');
    for(const [ang,len] of [[-34,13],[-11,16],[11,16],[34,13]])rotate(ang+3*s(t/300),Offset(px,30),()=>{drawOval(FEATHER,Offset(px-1.5,30),Size(3,len));drawLine(alpha(FEATHER_DARK,0.9),Offset(px,30.5),Offset(px,30+len-1),0.45,'round')});
    drawCircle('#2F6FD6',1.6,Offset(px,30))})}
