// Design copy of app/src/main/java/com/rollingrecorder/Cats.kt. Same coordinates (168 x 60), same call shapes.
const BLACK='#1F1F25', BLACK_LIGHT='#50505C', BLACK_DARK='#0A0A0D', BLACK_EYE='#BED34A';
const TABBY='#8E7D64', TABBY_LIGHT='#C6B699', TABBY_DARK='#4E4234', STRIPE='#352C24', TABBY_EYE='#9FD07E', BELLY='#D9C7AD';
const MUZZLE='#F1E9DB', NOSE='#DC8F8C', EAR_PINK='#C98A86', INK='#141414', BLUSH='#FF7A9A', RAISED='#282828', MIST='#B3B3B3', WHITE='#FFFFFF', YARN='#C8323A', YARN_DARK='#7A1D22', FEATHER='#A9DDE3', FEATHER_DARK='#6FB3BF';
const CONFETTI=[BLACK_EYE,TABBY_EYE,'#7C9CFF','#E056FD','#FFD54F'];

function popIn(x){const c1=1.70158,c3=c1+1,y=x-1;return 1+c3*y*y*y+c1*y*y}
function envelope(p,a,b,c,d){return (p<a||p>d)?0:(p<b)?popIn((p-a)/(b-a)):(p<c)?1:1-(p-c)/(d-c)}
function blink(t,off){const ph=((t+off)%4600)/4600;return ph<0.92?1:1-0.9*Math.min(1,Math.max(0,1-abs(ph-0.96)/0.04))}
function bez(p0,p1,p2,p3,u){const v=1-u;return Offset(v*v*v*p0.x+3*v*v*u*p1.x+3*v*u*u*p2.x+u*u*u*p3.x, v*v*v*p0.y+3*v*v*u*p1.y+3*v*u*u*p2.y+u*u*u*p3.y)}

function drawDuo(m,t){
  const cyc=(t%5000)/5000;
  const talk1=m==='CHAT'&&cyc>=0.06&&cyc<=0.48, talk2=m==='CHAT'&&cyc>=0.56&&cyc<=0.98;
  const breathe=1+0.02*s(t/900);
  const hop1 = m==='CELEBRATE' ? -7*abs(s(t/650*PI)) : 0;             // only the light one jumps
  const sway=(on,start)=>on?2*s((cyc-start)/0.12*2*PI):0;
  const squash=on=>on?1+0.04*abs(s(t/120)):(m==='CHAT'?1:breathe);
  const f=featherAt(t);
  const look1 = m==='CELEBRATE'?aim(Offset(30,22),f):talk2?Offset(1.2,0):m==='LISTEN'?Offset(0.8,0.8):m==='THINK'?Offset(0.4,-1.4):Offset(0.6,0.3);
  const look2 = m==='CELEBRATE'?aim(Offset(132,26),f):talk1?Offset(-1.8,0):m==='LISTEN'?Offset(-1,1):m==='THINK'?Offset(-1.2,1.6):Offset(-0.8,0.3);
  if(m==='CELEBRATE') plumes(t);
  if(m==='THINK') yarnTangle();
  if(m==='LISTEN') soundBars(t);
  groundShadow(30,hop1,m==='SLEEP'?17:14); groundShadow(133,0,m==='SLEEP'?28:26);
  withTransform(x=>{x.translate(0,hop1+(talk1?-1.5*abs(s((cyc-0.06)/0.12*2*PI)):0));x.rotate(sway(talk1,0.06),Offset(30,54));x.scale(1,squash(talk1),Offset(30,54))},()=>blackCat(m,t,talk1,look1));
  withTransform(x=>{x.translate(0,talk2?-1*abs(s((cyc-0.56)/0.12*2*PI)):0);x.scale(1,squash(talk2),Offset(132,54))},()=>tabby(m,t,talk2,look2));
  if(m==='CHAT'){bubble(46,16,envelope(cyc,0.06,0.10,0.44,0.48),Offset(46,28),true,t);bubble(76,14,envelope(cyc,0.56,0.60,0.94,0.98),Offset(112,28),false,t+500)}
  else if(m==='THINK'){bubble(46,16,0.85+0.05*s(t/400),Offset(46,28),true,t);bubble(76,14,0.85+0.05*s(t/400+2),Offset(112,28),false,t+330)}
  else if(m==='SLEEP') snores(t);
  else if(m==='CELEBRATE') feather(t);
}
function aim(from,to){const dx=to.x-from.x,dy=to.y-from.y,d=Math.hypot(dx,dy)||1;return Offset(1.8*dx/d,1.8*dy/d)}
function groundShadow(cx,hop,half){const k=1+hop/18;drawOval(alpha('#000000',0.35*k),Offset(cx-half*k,54.5),Size(2*half*k,3.5))}

// ---------- the black cat: slim, upright, glossy; the watcher ----------
function blackCat(m,t,talking,look){
  const rim=alpha(BLACK_LIGHT,0.6);
  if(m==='SLEEP'){blackCurled(t);return}
  const fur=radialGradient([BLACK_LIGHT,BLACK,BLACK_DARK],Offset(25,28),30);
  const sw=s(t/(m==='LISTEN'?320:900))*(m==='THINK'?7:5);
  const tail=Path();
  if(m==='CELEBRATE'){const q=1.5*s(t/90);tail.moveTo(40,52);tail.cubicTo(48,52,50+q,40,47+q,30);tail.cubicTo(46+q,26,50+q,24,52+q,27)}   // straight up, quivering: the greeting
  else{tail.moveTo(40,52);tail.cubicTo(52,54,55+sw,42,49+sw*0.6,33);tail.cubicTo(47+sw*0.6,29,51+sw*0.6,27,53+sw*0.6,30)}
  drawPath(tail,BLACK,Stroke(3.6,'round'));drawPath(tail,rim,Stroke(0.8,'round'));
  const body=Path();body.moveTo(18,54);body.cubicTo(16,45,21,36,30,35);body.cubicTo(39,36,44,45,42,54);body.close();
  drawPath(body,fur);drawPath(body,rim,Stroke(0.8));
  const sheen=Path();sheen.moveTo(37,39);sheen.quadraticTo(42,44,41,51);drawPath(sheen,alpha(WHITE,0.12),Stroke(1.2,'round'));
  for(const px of [22,31])drawOval(rim,Offset(px,51.5),Size(7,3),Stroke(0.7));
  if(m==='CELEBRATE'){const r=4*max(0,s(t/260));const paw=Offset(45+r,35-r*0.8);                 // a front paw up, swatting at the feather
    drawLine(BLACK,Offset(37,47),paw,4,'round');drawLine(rim,Offset(37,47),paw,0.8,'round');drawCircle(BLACK,2.4,paw);drawCircle(rim,2.4,paw,Stroke(0.8))}
  const tilt=m==='THINK'?-10:(talking?-4*abs(s(t/150)):0);                        // chin up at the wall while thinking
  rotate(tilt,Offset(30,34),()=>{
    const hy=23;
    ears(Offset(30,hy),11,m,t,fur,alpha(EAR_PINK,0.8),rim,1);
    drawOval(fur,Offset(17,hy-11),Size(26,22));drawOval(rim,Offset(17,hy-11),Size(26,22),Stroke(0.8));
    drawOval(alpha(WHITE,0.16),Offset(21,hy-8),Size(7,3.5));
    cheeks([Offset(21.5,hy+3.5),Offset(38.5,hy+3.5)],m,talking,0.35);
    const style=(m==='CELEBRATE'||m==='LISTEN')?'wide':'open';
    const lk=m==='THINK'?Offset(look.x,-1.6):look;
    catEye(Offset(24.5,hy-0.5),4.2,BLACK_EYE,style,blink(t,0),lk,BLACK_LIGHT);
    catEye(Offset(35.5,hy-0.5),4.2,BLACK_EYE,style,blink(t,0),lk,BLACK_LIGHT);
    nose(Offset(30,hy+4.6),BLACK_LIGHT,0.8);
    catMouth(Offset(30,hy+5.6),m,t,talking,BLACK_LIGHT,0.9);
    whiskers(Offset(30,hy+4.6),alpha(WHITE,0.3),10);
  });
}
function blackCurled(t){   // asleep: a tight loaf on the sill, tail wrapped round the front
  const rim=alpha(BLACK_LIGHT,0.6), br=0.6*s(t/1100);
  const fur=radialGradient([BLACK_LIGHT,BLACK,BLACK_DARK],Offset(26,44),22);
  drawOval(fur,Offset(14,40-br),Size(32,15+br));drawOval(rim,Offset(14,40-br),Size(32,15+br),Stroke(0.8));
  const tail=Path();tail.moveTo(44,50);tail.quadraticTo(40,56,22,54.5);tail.quadraticTo(16,54,15,51);
  drawPath(tail,BLACK,Stroke(3,'round'));drawPath(tail,rim,Stroke(0.7,'round'));
  ears(Offset(23,44),8,'SLEEP',t,fur,alpha(EAR_PINK,0.7),rim,1);
  drawOval(fur,Offset(13.5,37.5),Size(19,15));drawOval(rim,Offset(13.5,37.5),Size(19,15),Stroke(0.8));
  catEye(Offset(19,44.5),2.8,BLACK_EYE,'shut',1,Offset(0,0),BLACK_LIGHT);catEye(Offset(27,44.5),2.8,BLACK_EYE,'shut',1,Offset(0,0),BLACK_LIGHT);
  nose(Offset(23,48),BLACK_LIGHT,0.7);whiskers(Offset(23,48),alpha(WHITE,0.25),7);
}

// ---------- the tabby: round and heavy, mackerel stripes, white chin; the loaf ----------
function tabby(m,t,talking,look){
  if(m==='SLEEP'){tabbySprawl(t);return}
  const fur=radialGradient([TABBY_LIGHT,TABBY,TABBY_DARK],Offset(124,34),42);
  const sw=s(t/(m==='LISTEN'?320:1100))*5;
  const p0=Offset(158,47),p1=Offset(168,53),p2=Offset(158+sw*0.3,59.5),p3=Offset(138+sw,58);
  const tail=Path();tail.moveTo(p0.x,p0.y);tail.cubicTo(p1.x,p1.y,p2.x,p2.y,p3.x,p3.y);
  drawPath(tail,TABBY_DARK,Stroke(4.6,'round'));drawPath(tail,TABBY,Stroke(2.8,'round'));
  for(const u of [0.35,0.6,0.85]){const q=bez(p0,p1,p2,p3,u);drawCircle(alpha(STRIPE,0.7),1.6,q,Stroke(0.9))}   // tail rings
  const body=Path();body.moveTo(104,55);body.cubicTo(101,41,112,33,132,33);body.cubicTo(152,33,163,41,160,55);body.close();
  drawPath(body,fur);
  for(let i=0;i<6;i++){const x=110+i*9;const p=Path();p.moveTo(x,39);p.quadraticTo(x+3,46,x-0.5,53);drawPath(p,alpha(STRIPE,0.55),Stroke(1.8,'round'))}
  drawOval(alpha(TABBY_LIGHT,0.4),Offset(122,44),Size(20,11));
  const bat=m==='CELEBRATE'?max(0,s(t/180)):0;                                        // near paw bats at the feather
  paw(Offset(134,52),TABBY_LIGHT);
  rotate(-30*bat,Offset(128,54),()=>paw(Offset(120,52),TABBY_LIGHT));
  const hy=26, turn=talking?-3*abs(s(t/160)):0;
  rotate(turn,Offset(132,36),()=>{
    ears(Offset(132,hy),13,m,t,fur,EAR_PINK,null,-1);
    drawOval(fur,Offset(115,hy-13.5),Size(34,27));
    for(const dx of [-5,0,5])drawLine(alpha(STRIPE,0.75),Offset(132+dx,hy-12),Offset(132+dx*0.75,hy-6.5),1.5,'round');
    drawLine(alpha(STRIPE,0.6),Offset(117.5,hy-1),Offset(121,hy+1.5),1.2,'round');drawLine(alpha(STRIPE,0.6),Offset(146.5,hy-1),Offset(143,hy+1.5),1.2,'round');
    drawOval(MUZZLE,Offset(123.5,hy+2.5),Size(17,9.5));
    cheeks([Offset(119.5,hy+5.5),Offset(144.5,hy+5.5)],m,talking,0.5);
    const style=m==='CELEBRATE'?'wide':'open';
    const open=(m==='LISTEN'?0.55:1)*blink(t,1700);                                    // the half-lidded stare
    catEye(Offset(124.5,hy-1.5),4.6,TABBY_EYE,style,open,look,STRIPE);catEye(Offset(139.5,hy-1.5),4.6,TABBY_EYE,style,open,look,STRIPE);
    nose(Offset(132,hy+5),NOSE,0.9);catMouth(Offset(132,hy+6.4),m,t,talking,STRIPE,1.1);whiskers(Offset(132,hy+5.5),alpha(WHITE,0.7),17);
  });
}
function paw(c,color){drawOval(alpha(color,0.8),Offset(c.x-5,c.y-2),Size(10,4));for(const dx of [-1.7,1.7])drawLine(alpha(STRIPE,0.45),Offset(c.x+dx,c.y+0.2),Offset(c.x+dx,c.y+1.6),0.7,'round')}
function tabbySprawl(t){   // asleep: flat on the back, belly up, paws in the air, one leg stretched out
  const fur=radialGradient([TABBY_LIGHT,TABBY,TABBY_DARK],Offset(130,42),40), br=0.5*s(t/1100);
  drawLine(TABBY_DARK,Offset(154,47),Offset(165,41),4.5,'round');drawLine(TABBY,Offset(154,47),Offset(165,41),2.8,'round');
  const body=Path();body.moveTo(112,54.5);body.cubicTo(110,38-br,126,35-br,138,36-br);body.cubicTo(152,37-br,162,43,160,54.5);body.close();
  drawPath(body,fur);
  for(let i=0;i<5;i++){const x=118+i*8;const p=Path();p.moveTo(x,37);p.quadraticTo(x+2,42,x+1,46);drawPath(p,alpha(STRIPE,0.55),Stroke(1.6,'round'))}
  drawOval(alpha(BELLY,0.7),Offset(122,42),Size(32,12));
  for(const [x0,x1,ph] of [[128,125,0],[140,144,1.2]]){const w=1.5*s(t/700+ph);drawLine(TABBY_DARK,Offset(x0,41),Offset(x1+w,31),4,'round');drawLine(TABBY,Offset(x0,41),Offset(x1+w,31),2.4,'round');drawCircle(alpha(TABBY_LIGHT,0.8),1.9,Offset(x1+w,31))}
  const hx=109,hy=45;
  rotate(-25,Offset(hx,hy),()=>{
    ears(Offset(hx,hy),10,'SLEEP',t,fur,EAR_PINK,null,1);
    drawOval(fur,Offset(hx-12,hy-9.5),Size(24,19));
    for(const dx of [-3.5,0,3.5])drawLine(alpha(STRIPE,0.7),Offset(hx+dx,hy-9),Offset(hx+dx*0.75,hy-4.5),1.2,'round');
    drawOval(MUZZLE,Offset(hx-6,hy+1.5),Size(12,7));
    cheeks([Offset(hx-8.5,hy+3.5),Offset(hx+8.5,hy+3.5)],'SLEEP',false,0.4);
    catEye(Offset(hx-5,hy-1),3.2,TABBY_EYE,'shut',1,Offset(0,0),STRIPE);catEye(Offset(hx+5,hy-1),3.2,TABBY_EYE,'shut',1,Offset(0,0),STRIPE);
    nose(Offset(hx,hy+3.4),NOSE,0.8);whiskers(Offset(hx,hy+3.8),alpha(WHITE,0.55),11);
  });
}
function yarnTangle(){   // the red yarn mess on the rug that the tabby contemplates
  const p=Path();p.moveTo(98,55);p.cubicTo(104,48,110,58,106,51);p.cubicTo(103,46,112,47,110,53);p.cubicTo(109,57,100,56,104,50);p.cubicTo(107,46,114,52,112,56);
  drawPath(p,YARN,Stroke(1.2,'round'));
  const strand=Path();strand.moveTo(112,56);strand.quadraticTo(122,58,128,55);drawPath(strand,alpha(YARN,0.8),Stroke(1,'round'));
}
function featherAt(t){const a=24*s(t/520)*PI/180;return Offset(84+38*Math.sin(a),-4+38*Math.cos(a))}
function feather(t){   // the feather toy on its string, swung from a hand above the picture
  const a=24*s(t/520),px=84,py=-4;
  rotate(a,Offset(px,py),()=>{
    drawLine(alpha(WHITE,0.45),Offset(px,py),Offset(px,33),0.7,'round');
    for(const [ang,len] of [[-34,15],[-11,18],[11,18],[34,15]])rotate(ang+3*s(t/300),Offset(px,33),()=>{drawOval(FEATHER,Offset(px-1.6,33),Size(3.2,len));drawLine(alpha(FEATHER_DARK,0.9),Offset(px,33.5),Offset(px,33+len-1),0.45,'round');for(let k=4;k<len-2;k+=3)drawLine(alpha(WHITE,0.35),Offset(px-1.4,33+k),Offset(px+1.4,33+k+0.8),0.3,'round')});
    drawCircle('#2F6FD6',1.7,Offset(px,33));
  });
}
function plumes(t){   // loose bits of feather drifting down
  for(let i=0;i<8;i++){const p=((t/1600)+i*0.137)%1,x=(i*41+23)%168+5*s(t/400+i),y=-6+70*p;
    rotate(t/6+i*45,Offset(x,y),()=>drawOval(alpha(FEATHER,0.9-p*0.6),Offset(x-1.2,y-2.6),Size(2.4,5.2)))}
}

// ---------- shared parts ----------
function ears(c,r,m,t,fur,inner,rim,toward){   // toward: +1 if the middle of the picture is to this cat's right
  const up=m==='LISTEN'?3:m==='SLEEP'?-2.5:0;
  const swivel=m==='LISTEN'?10+6*s(t/700):0;                                            // the ear nearest the sound turns to it
  const twitch=m==='LISTEN'?8*max(0,s(t/260))*max(0,s(t/1900)):0;
  function ear(side,tilt){const bx=c.x+side*r*0.72,by=c.y-r*0.35;
    rotate(side*tilt,Offset(bx,by),()=>{const p=Path();p.moveTo(bx-side*r*0.1,by+r*0.1);p.lineTo(bx+side*r*0.3,by-r*1.35-up);p.lineTo(bx+side*r*0.55,by+r*0.15);p.close();
      const q=Path();q.moveTo(bx+side*r*0.06,by+r*0.05);q.lineTo(bx+side*r*0.3,by-r*0.95-up);q.lineTo(bx+side*r*0.42,by+r*0.08);q.close();
      drawPath(p,fur);if(rim)drawPath(p,rim,Stroke(0.8));drawPath(q,inner)})}
  ear(-toward,m==='THINK'?14:twitch);
  ear(toward,m==='SLEEP'?6:swivel);
}
function cheeks(at,m,talking,k){const a=k*((talking||m==='CELEBRATE')?1:0.6);at.forEach(o=>drawOval(alpha(BLUSH,a),Offset(o.x-3,o.y-1.6),Size(6,3.2)))}
function catEye(c,r,iris,style,open,look,lid){   // almond cat eye; style: slit | wide | shut | happy
  if(style==='shut'){drawArc(lid,20,140,false,Offset(c.x-r*0.8,c.y-r*0.6),Size(r*1.6,r*1.0),Stroke(1.3,'round'));return}
  if(style==='happy'){drawArc(lid,200,140,false,Offset(c.x-r*0.8,c.y-r*0.2),Size(r*1.6,r*1.2),Stroke(1.5,'round'));return}
  const h=r*0.9*open;                                                                // big, round-ish eyes
  const al=Path();al.moveTo(c.x-r,c.y+h*0.1);al.quadraticTo(c.x,c.y-h*2.1,c.x+r,c.y+h*0.1);al.quadraticTo(c.x,c.y+h*1.7,c.x-r,c.y+h*0.1);al.close();
  drawPath(al,iris);drawPath(al,alpha(lid,0.7),Stroke(0.6));
  if(open>0.35){const wide=style==='wide',pw=wide?r*1.0:r*0.62,ph=wide?h*0.9:h*0.8;const pc=Offset(c.x+look.x*0.6,c.y+look.y*0.4);
    drawOval(INK,Offset(pc.x-pw/2,pc.y-ph),Size(pw,2*ph));
    drawCircle(alpha(WHITE,0.95),r*0.26,Offset(pc.x-r*0.25,pc.y-h*0.4));drawCircle(alpha(WHITE,0.7),r*0.12,Offset(pc.x+r*0.22,pc.y+h*0.3))}
}
function nose(c,color,k){const p=Path();p.moveTo(c.x-1.7*k,c.y-1*k);p.lineTo(c.x+1.7*k,c.y-1*k);p.lineTo(c.x,c.y+1.3*k);p.close();drawPath(p,color)}
function catMouth(c,m,t,talking,color,w){const k=1.6*w;
  if(talking){const h=1.2+2*abs(s(t/110));drawOval(color,Offset(c.x-k*0.6,c.y+0.5),Size(k*1.2,h));return}
  const p=Path();
  if(m==='CELEBRATE'){p.moveTo(c.x-k*2.2,c.y);p.quadraticTo(c.x-k,c.y+2.4,c.x,c.y+0.8);p.quadraticTo(c.x+k,c.y+2.4,c.x+k*2.2,c.y);drawPath(p,color,Stroke(1.1,'round'))}
  else{p.moveTo(c.x-k*2,c.y+0.6);p.quadraticTo(c.x-k,c.y+1.8,c.x,c.y);p.quadraticTo(c.x+k,c.y+1.8,c.x+k*2,c.y+0.6);drawPath(p,color,Stroke(0.9,'round'))}
}
function whiskers(c,color,len){for(const side of [-1,1])[-2.5,0,2.5].forEach((dy,i)=>drawLine(color,Offset(c.x+side*4,c.y-1+i),Offset(c.x+side*len,c.y+dy),0.55,'round'))}
function bubble(x,y,k,pivot,left,t){if(k<=0.01)return;
  scale(k,k,pivot,()=>{const tail=Path();if(left){tail.moveTo(x+6,y+18);tail.lineTo(x+1,y+26);tail.lineTo(x+12,y+21)}else{tail.moveTo(x+30,y+18);tail.lineTo(x+35,y+26);tail.lineTo(x+24,y+21)}tail.close();
    drawRoundRect(alpha('#000000',0.3),Offset(x+1,y+2),Size(36,20),10);drawPath(tail,RAISED);drawRoundRect(RAISED,Offset(x,y),Size(36,20),10);drawRoundRect(alpha(WHITE,0.12),Offset(x,y),Size(36,20),10,Stroke(0.8));
    for(let i=0;i<3;i++){const dy=-2*max(0,s(t/1000*2*PI-i*0.9));drawCircle(left?BLACK_EYE:TABBY_EYE,2.2,Offset(x+10+i*8,y+10+dy))}})}
function soundBars(t){for(let i=0;i<7;i++){const h=3+9*abs(s(t/(170+i*23)+i));drawRoundRect(alpha(WHITE,0.55),Offset(68+i*5,54-h),Size(2.6,h),1.3)}}
function snores(t){for(let k=0;k<3;k++){const p=((t/2600)+k/3)%1,size=3+3.5*p,x=118+14*p,y=30-20*p;const z=Path();z.moveTo(x,y);z.lineTo(x+size,y);z.lineTo(x,y+size);z.lineTo(x+size,y+size);drawPath(z,alpha(MIST,1-p),Stroke(1.2,'round'))}}
function confetti(t){for(let i=0;i<16;i++){const p=((t/1100)+i*0.137)%1,x=(i*37+11)%168+4*s(t/300+i),y=-8+70*p;rotate(t/4+i*40,Offset(x,y),()=>drawRect(alpha(CONFETTI[i%CONFETTI.length],1-p*0.6),Offset(x-1.6,y-0.8),Size(3.2,1.6)))}}
