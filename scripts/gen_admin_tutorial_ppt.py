# -*- coding: utf-8 -*-
"""生成 admin-web 管理端教学 PPT（单文件 HTML，内嵌截图 base64）。"""
import base64
import os

IMG_DIR = r"d:\文档\enterprise-canteen\enterprise-canteen\docs\tutorials\admin-web\img"
OUT = r"d:\文档\enterprise-canteen\enterprise-canteen\docs\tutorials\admin-web\admin-web-教程.html"


def data_uri(name):
    p = os.path.join(IMG_DIR, name)
    with open(p, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return "data:image/png;base64," + b64


# 预载所有截图（占位符名 -> 文件）
IMG = {
    "s01": data_uri("s01_store.png"),
    "s01b": data_uri("s01b_switch_confirm.png"),
    "s02": data_uri("s02_admin.png"),
    "s03": data_uri("s03_timer.png"),
    "s04": data_uri("s04_department.png"),
    "s05": data_uri("s05_employee.png"),
    "s06": data_uri("s06_dish.png"),
    "s07": data_uri("s07_menu.png"),
}

CSS = """
*{margin:0;padding:0;box-sizing:border-box}
html,body{background:#eef0f4;height:100%;overflow:hidden;font-family:'PingFang SC','Microsoft YaHei',system-ui,sans-serif}
#canvas{position:fixed;top:50%;left:50%;width:1280px;height:720px;background:#fff;
  transform:translate(-50%,-50%) scale(1);box-shadow:0 10px 60px rgba(0,0,0,.16);
  transform-origin:center center;overflow:hidden}
.slide{position:absolute;inset:0;opacity:0;visibility:hidden;transition:opacity .5s cubic-bezier(.2,.7,.2,1),visibility .5s;
  padding:56px 72px 64px;display:grid;grid-template-rows:auto 1fr;align-content:start}
.slide.active{opacity:1;visibility:visible}
.slide .anim{opacity:0;transform:translateY(14px)}
.slide.active .anim{animation:rise .6s cubic-bezier(.2,.7,.2,1) forwards}
@keyframes rise{to{opacity:1;transform:none}}
.dlay1{animation-delay:.05s!important}.dlay2{animation-delay:.15s!important}.dlay3{animation-delay:.25s!important}

/* chrome */
.chrome-top{position:absolute;top:0;left:72px;right:72px;height:44px;display:flex;align-items:center;justify-content:space-between;
  font-size:13px;color:#8a93a6}
.chrome-top .brand{font-weight:600;color:#2f7d5c}
.chrome-top .tag{color:#b0b7c4}
.chrome-bottom{position:absolute;bottom:0;left:0;right:0;height:44px;display:flex;align-items:center;justify-content:space-between;
  padding:0 72px;font-size:12px;color:#b0b7c4}
.dots{position:absolute;bottom:14px;left:50%;transform:translateX(-50%);display:flex;gap:8px}
.dots b{width:8px;height:8px;border-radius:50%;background:#d5dae3;cursor:pointer;transition:.3s}
.dots b.on{width:22px;border-radius:6px;background:#2f7d5c}

/* typography helpers */
.kicker{font-size:13px;letter-spacing:2px;color:#2f7d5c;font-weight:600;text-transform:uppercase}
h1{font-size:40px;color:#1c2430;font-weight:700;line-height:1.2}
h2{font-size:26px;color:#1c2430;font-weight:700}
.lead{font-size:16px;color:#4a5568;line-height:1.7}
.steps{display:flex;flex-wrap:wrap;gap:10px;margin-top:18px}
.step{background:#f2f6f4;border:1px solid #dfe8e2;border-radius:12px;padding:10px 14px;font-size:14px;color:#2f7d5c;font-weight:600}
.step i{font-style:normal;color:#b0b7c4;font-weight:400;margin-right:6px}

/* number chip */
.num{display:inline-flex;width:34px;height:34px;border-radius:10px;background:#2f7d5c;color:#fff;font-weight:700;
  align-items:center;justify-content:center;font-size:18px;flex:none}

/* content rows */
.row{display:grid;grid-template-columns:1fr 1fr;gap:22px;align-items:center;min-height:0}
.row.rev{grid-template-columns:1fr 1fr}
.row .txt{min-height:0}
.row .shot{background:#eef0f4;border-radius:14px;overflow:hidden;min-height:0;box-shadow:0 6px 24px rgba(31,45,61,.10)}
.row .shot img{width:100%;height:100%;object-fit:contain;min-height:0;display:block}
.std-effect{transition:transform .25s}
.std-effect:hover{transform:translateY(-3px)}
.flow{display:flex;flex-direction:column;gap:14px;margin-top:10px}
.flow .f{display:flex;align-items:center;gap:14px;background:#fff;border:1px solid #e6ebf0;border-radius:14px;padding:14px 16px}
.flow .f .t b{font-size:16px;color:#1c2430;display:block}
.flow .f .t span{font-size:13px;color:#7a8494}
.flow .arrow{color:#8a93a6;font-size:20px;margin-left:34px}
"""

# 每个 slide 的 body HTML
SLIDES = []

# 1 Cover
SLIDES.append("""
  <div class="anim" style="align-self:center;justify-self:center;text-align:center;max-width:760px">
    <div class="kicker" style="margin-bottom:16px">企业智慧食堂 · 管理端开通教程</div>
    <h1 style="font-size:52px">从 0 到 1 开通一个食堂</h1>
    <div class="lead" style="margin-top:20px">一张脉络图，带你走完食堂初始化全流程：食堂 → 管理员 → 时段 → 部门 → 员工 → 菜品 → 菜单</div>
    <div class="steps" style="justify-content:center;margin-top:34px">
       <span class="step"><i>壹</i>创建食堂</span><span class="step"><i>贰</i>食堂管理员</span>
       <span class="step"><i>叁</i>就餐时段</span><span class="step"><i>肆</i>创建部门</span>
       <span class="step"><i>伍</i>创建/导入员工</span><span class="step"><i>陆</i>添加菜品</span><span class="step"><i>柒</i>创建菜单</span>
    </div>
  </div>
""")

# 2 脉络总览
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">整体脉络</div>
    <h1 style="margin-top:6px">七步走，先配骨架再填内容</h1>
  </div>
  <div class="flow anim dlay2" style="align-self:center">
    <div class="f"><div class="num">1</div><div class="t"><b>创建食堂</b><span>系统默认无食堂，须先建食堂才能进入管理</span></div></div>
    <div class="arrow">↓</div>
    <div class="f"><div class="num">2</div><div class="t"><b>创建食堂管理员</b><span>为食堂分配管理员账号与权限</span></div></div>
    <div class="arrow">↓</div>
    <div class="f"><div class="num">3</div><div class="t"><b>就餐时段</b><span>配置早/中/晚时段，供点餐与菜单使用</span></div></div>
    <div class="arrow">↓</div>
    <div class="f"><div class="num">4</div><div class="t"><b>创建部门 → 创建/导入员工</b><span>先建部门，再录员工并归属部门</span></div></div>
    <div class="arrow">↓</div>
    <div class="f"><div class="num">5</div><div class="t"><b>添加菜品 → 创建菜单</b><span>录入菜品后按日期编排三餐菜单</span></div></div>
  </div>
""")

# 3 创建食堂（双图）
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第一步 · 创建食堂</div>
    <h1 style="margin-top:6px">没有食堂，一切无从谈起</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center;grid-template-columns:1.15fr 1fr">
    <div class="shot std-effect"><img src="{s01}" alt="食堂管理列表页截图"/></div>
    <div class="txt">
      <div class="lead" style="margin-bottom:14px">登录后进入<b>食堂管理</b>，系统默认无食堂。点击「新建食堂」填写名称、营业信息即可创建。</div>
      <div class="shot std-effect" style="max-height:230px"><img src="{s01b}" alt="切换食堂确认弹窗截图"/></div>
      <div class="lead" style="margin-top:12px;font-size:14px">创建后可<b>进入管理</b>，多食堂场景下通过左上角「切换」在新旧食堂间切换。</div>
    </div>
  </div>
""")

# 4 食堂管理员
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第二步 · 创建食堂管理员</div>
    <h1 style="margin-top:6px">给食堂配一个「管家」</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s02}" alt="账号管理/食堂管理员列表截图"/></div>
    <div class="txt">
      <div class="lead">在<b>账号管理</b>中为食堂创建管理员：设置账号名、密码（系统自动生成，降低泄露风险）、姓名与角色权限。</div>
      <div class="steps"><span class="step"><i>1</i>新增账号</span><span class="step"><i>2</i>绑定食堂</span><span class="step"><i>3</i>分配角色</span></div>
    </div>
  </div>
""")

# 5 就餐时段
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第三步 · 就餐时段</div>
    <h1 style="margin-top:6px">定好三餐的「时间窗」</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s03}" alt="就餐时段配置页截图"/></div>
    <div class="txt">
      <div class="lead">在<b>就餐时段</b>中按餐次（早餐/午餐/晚餐）配置起止时间。时段是后续菜品适用餐次、点单与菜单编排的时间依据。</div>
      <div class="steps"><span class="step"><i>例</i>早 07:00–09:00</span><span class="step"><i>例</i>午 11:30–13:30</span><span class="step"><i>例</i>晚 17:30–19:30</span></div>
    </div>
  </div>
""")

# 6 部门
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第四步 · 创建部门</div>
    <h1 style="margin-top:6px">先搭好组织架构</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s04}" alt="部门管理页截图"/></div>
    <div class="txt">
      <div class="lead">在<b>部门管理</b>中按公司组织架构创建部门，支持多级父子结构。员工随后按部门归属，便于统计与订餐汇总。</div>
      <div class="steps"><span class="step">技术部</span><span class="step">人力资源部</span><span class="step">财务部</span><span class="step">行政部</span></div>
    </div>
  </div>
""")

# 7 员工
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第五步 · 创建 / 导入员工</div>
    <h1 style="margin-top:6px">把「人」录进系统</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s05}" alt="员工管理页截图"/></div>
    <div class="txt">
      <div class="lead">在<b>员工管理</b>中可<b>单个添加</b>，也可下载模板<b>批量导入</b>。填写姓名、卡号、手机号并归属部门，设置初始余额与状态。</div>
      <div class="steps"><span class="step"><i>1</i>添加/导入</span><span class="step"><i>2</i>绑定卡号</span><span class="step"><i>3</i>归属部门</span><span class="step"><i>4</i>设置余额</span></div>
    </div>
  </div>
""")

# 8 菜品
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第六步 · 添加菜品</div>
    <h1 style="margin-top:6px">把「菜」录进系统</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s06}" alt="菜品管理页截图"/></div>
    <div class="txt">
      <div class="lead">在<b>菜品管理</b>中录入菜品：名称、图片、价格、适用餐次、库存与单次限购，并控制上下架状态。</div>
      <div class="steps"><span class="step">名称/图片</span><span class="step">价格</span><span class="step">适用餐次</span><span class="step">库存/限购</span></div>
    </div>
  </div>
""")

# 9 菜单
SLIDES.append("""
  <div class="anim dlay1">
    <div class="kicker">第七步 · 创建菜单</div>
    <h1 style="margin-top:6px">把菜排进每天的餐桌</h1>
  </div>
  <div class="row anim dlay2" style="align-self:center">
    <div class="shot std-effect"><img src="{s07}" alt="菜单管理月历页截图"/></div>
    <div class="txt">
      <div class="lead">在<b>菜单管理</b>中按日期编排每日早/中/晚三餐菜品。支持月历快捷切换、按起始日期批量粘贴与批量发布，员工即可在 H5 端订餐。</div>
      <div class="steps"><span class="step"><i>1</i>选日期</span><span class="step"><i>2</i>排三餐</span><span class="step"><i>3</i>批量发布</span></div>
    </div>
  </div>
""")

# 10 Closing
SLIDES.append("""
  <div class="anim" style="align-self:center;justify-self:center;text-align:center;max-width:800px">
    <div class="kicker" style="margin-bottom:16px">开通完成</div>
    <h1 style="font-size:48px">至此，食堂即可正式营业</h1>
    <div class="lead" style="margin-top:20px">员工通过 H5 端登录订餐，管理员可在后台继续配置通知、供应商、采购、库存与报表。</div>
    <div class="steps" style="justify-content:center;margin-top:34px">
       <span class="step">通知管理</span><span class="step">供应商</span><span class="step">采购</span><span class="step">库存</span><span class="step">报表统计</span>
    </div>
  </div>
""")

# 组装 HTML
slides_html = []
for i, body in enumerate(SLIDES, 1):
    body = body.format(**IMG)
    slides_html.append(f'<section class="slide" id="s{i}">{body}</section>')

slides_joined = "\n".join(slides_html)
dots = "".join(f'<b data-i="{i}" class="{"on" if i==1 else ""}"></b>' for i in range(1, len(SLIDES) + 1))

html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>企业智慧食堂 · 管理端开通教程</title>
<style>{CSS}</style>
</head>
<body>
<div id="canvas">
  <div class="chrome-top"><span class="brand">企业智慧食堂</span><span class="tag">管理端 · 开通教程</span></div>
  {slides_joined}
  <div class="chrome-bottom"><span>企业智慧食堂 · admin-web</span><span class="page"></span></div>
  <div class="dots">{dots}</div>
</div>
<script>
(function(){{
  var can=document.getElementById('canvas');
  var slides=[].slice.call(document.querySelectorAll('.slide'));
  var dots=[].slice.call(document.querySelectorAll('.dots b'));
  var page=document.querySelector('.page');
  var cur=0,n=slides.length;
  function scale(){{
    var s=Math.min(window.innerWidth/1280, window.innerHeight/720);
    can.style.transform='translate(-50%,-50%) scale('+s+')';
  }}
  window.addEventListener('resize',scale);scale();
  function go(i){{
    if(i<0)i=0;if(i>n-1)i=n-1;
    slides[cur].classList.remove('active');
    dots[cur].classList.remove('on');
    cur=i;
    slides[cur].classList.add('active');
    dots[cur].classList.add('on');
    page.textContent=(cur+1)+' / '+n;
  }}
  dots.forEach(function(d){{d.addEventListener('click',function(){{go(+d.getAttribute('data-i')-1);}});}});
  document.addEventListener('keydown',function(e){{
    if(e.key==='ArrowRight'||e.key==='PageDown')go(cur+1);
    if(e.key==='ArrowLeft'||e.key==='PageUp')go(cur-1);
    if(e.key==='Home')go(0);if(e.key==='End')go(n-1);
  }});
  var tx=0,ty=0;
  document.addEventListener('wheel',function(e){{e.preventDefault();go(cur+(e.deltaY>0?1:-1));}},{{passive:false}});
  var sx,sy;
  document.addEventListener('touchstart',function(e){{sx=e.touches[0].clientX;sy=e.touches[0].clientY;}},{{passive:true}});
  document.addEventListener('touchend',function(e){{
    var dx=e.changedTouches[0].clientX-sx, dy=e.changedTouches[0].clientY-sy;
    if(Math.abs(dy)>Math.abs(dx)&&Math.abs(dy)>40)go(cur+(dy<0?1:-1));
  }},{{passive:true}});
  go(0);
}})();
</script>
</body>
</html>
"""

with open(OUT, "w", encoding="utf-8") as f:
    f.write(html)
print("written:", OUT, round(os.path.getsize(OUT) / 1024, 1), "KB")