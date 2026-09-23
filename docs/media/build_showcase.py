"""Render the original mobby README artwork and illustrative video.

Requires Pillow and ffmpeg (the checked-in assets need neither at viewing time).
No device capture, account data, or gateway credentials are used.
"""
from pathlib import Path
import math
import subprocess
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
FONT = '/System/Library/Fonts/PingFang.ttc'
REG = ImageFont.truetype(FONT, 27)
MID = ImageFont.truetype(FONT, 34)
BOLD = ImageFont.truetype(FONT, 56)
SMALL = ImageFont.truetype(FONT, 22)
WHITE = '#f5f8ff'
MUTED = '#a2b3d0'
BG = '#0c1327'
CYAN = '#69ddd2'
PURPLE = '#ab9cff'


def svg_text(x, y, value, size=28, color='#f5f8ff', weight=500, anchor='start'):
    return f'<text x="{x}" y="{y}" fill="{color}" font-family="PingFang SC, Noto Sans CJK SC, sans-serif" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}">{value}</text>'


def render_svg():
    base = '''<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="760" viewBox="0 0 1440 760" role="img" aria-label="mobby Android 手机运行三种 Agent 的产品示意图"><defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#0c1327"/><stop offset="1" stop-color="#17223d"/></linearGradient><linearGradient id="accent" x2="1" y2="1"><stop stop-color="#78e5dd"/><stop offset="1" stop-color="#9e9cff"/></linearGradient><filter id="shadow"><feDropShadow dx="0" dy="18" stdDeviation="30" flood-color="#040817" flood-opacity=".55"/></filter></defs><rect width="1440" height="760" fill="url(#bg)"/><circle cx="1200" cy="70" r="340" fill="#445c9e" opacity=".15"/><circle cx="150" cy="750" r="290" fill="#2c9f9d" opacity=".10"/>'''
    s = [base]
    s += [svg_text(88, 107, 'mobby', 46, '#a9f3e9', 700), svg_text(88, 205, '把 Agent 带进手机', 68, weight=700), svg_text(88, 262, 'Claude Code · Codex · OpenCode', 31, '#d7e1f6'), svg_text(88, 325, '内置运行环境，在 Android 本机工作区执行任务。', 26, MUTED)]
    cards = [('01', '选择 Agent', '三种 CLI，统一对话入口'), ('02', '连接网关', '使用自己的模型与密钥'), ('03', '跟进执行', '回复与工具步骤实时可见')]
    for i, (num, title, sub) in enumerate(cards):
        y=386+i*101
        s += [f'<rect x="88" y="{y}" width="590" height="81" rx="22" fill="#202c48" stroke="#354562"/>', svg_text(116,y+50,num,25,CYAN,700),svg_text(181,y+43,title,26,WHITE,650),svg_text(181,y+68,sub,18,MUTED)]
    s += ['<rect x="833" y="56" width="438" height="656" rx="53" fill="#090e1d" stroke="#43516e" stroke-width="3" filter="url(#shadow)"/><rect x="848" y="73" width="408" height="624" rx="39" fill="#f8fafc"/><rect x="970" y="82" width="165" height="25" rx="13" fill="#090e1d"/>']
    s += [svg_text(880,150,'mobby',27,'#162039',700),f'<rect x="1181" y="125" width="48" height="42" rx="16" fill="#edf2f7"/>',svg_text(1205,154,'⋯',26,'#27344d',600,'middle')]
    s += ['<rect x="882" y="190" width="342" height="70" rx="22" fill="#edf5f4"/>',svg_text(903,219,'项目里的测试失败了，帮我查一下。',19,'#27344d'),svg_text(903,245,'工作区  ·  my-project',15,'#64748b')]
    s += [f'<rect x="882" y="284" width="342" height="254" rx="25" fill="#ffffff" stroke="#e2e8f0"/>',svg_text(902,322,'Codex 正在处理',21,'#17243c',650),svg_text(902,358,'✓ 读取测试报告',17,'#53657d'),svg_text(902,392,'✓ 检查相关文件',17,'#53657d'),svg_text(902,426,'● 修改并重新验证',17,'#118d87'),f'<rect x="901" y="460" width="294" height="48" rx="15" fill="#eef8f6"/>',svg_text(920,491,'执行步骤可展开查看',17,'#23645f')]
    s += ['<rect x="879" y="583" width="346" height="74" rx="25" fill="#ffffff" stroke="#dce4eb"/>',svg_text(903,629,'＋',31,'#3c5368'),svg_text(954,626,'继续向 Agent 提问…',18,'#8291a5'),'<circle cx="1185" cy="621" r="22" fill="#138b87"/>',svg_text(1185,630,'↑',24,'#ffffff',700,'middle')]
    s += [svg_text(88,716,'原创示意图  ·  非真实会话截图',17,'#8090ae'),'</svg>']
    (OUT/'overview.svg').write_text(''.join(s),encoding='utf-8')

    s=['''<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="550" viewBox="0 0 1440 550" role="img" aria-label="mobby 网关、Agent 与手机工作区的数据路径"><defs><linearGradient id="g" x2="1" y2="1"><stop stop-color="#101b31"/><stop offset="1" stop-color="#192f45"/></linearGradient><marker id="arrow" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto"><path d="M0 0 L12 6 L0 12" fill="#72d9d0"/></marker></defs><rect width="1440" height="550" fill="url(#g)"/>''',svg_text(72,91,'一次任务，两个边界',46,WHITE,700),svg_text(72,134,'本机执行命令；模型请求经你配置的网关发送。',24,MUTED)]
    items=[(72,'手机界面','选择 Agent、工作区','查看回复与步骤'),(522,'本地运行环境','Claude Code / Codex','OpenCode · 文件与命令'),(972,'你的模型网关','Responses / Messages','地址、模型与密钥')]
    for x,title,a,b in items:
        s += [f'<rect x="{x}" y="202" width="392" height="205" rx="31" fill="#24324d" stroke="#526381"/>',svg_text(x+30,258,title,32,WHITE,650),svg_text(x+30,320,a,23,'#a9eee3'),svg_text(x+30,356,b,20,MUTED)]
    for x in (464,914): s += [f'<path d="M{x} 304 H{x+49}" stroke="#72d9d0" stroke-width="4" marker-end="url(#arrow)"/>']
    s += [svg_text(72,480,'会话和工作区保留在手机',20,MUTED),svg_text(522,480,'执行受 Android 应用沙箱约束',20,MUTED),svg_text(972,480,'仅任务所需内容发送到网关',20,MUTED),'</svg>']
    (OUT/'how-it-works.svg').write_text(''.join(s),encoding='utf-8')


def text(d, xy, value, font, fill):
    d.text(xy, value, font=font, fill=fill)


def rr(d, box, radius, fill, outline=None, width=1):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def frame(seconds):
    im=Image.new('RGB',(1280,720),BG);d=ImageDraw.Draw(im)
    phase=seconds%3
    pulse=(1+math.sin(seconds*2*math.pi*1.2))/2
    d.ellipse((830,-270,1460,360),fill='#142c49')
    d.ellipse((-300,460,220,980),fill='#102e37')
    text(d,(67,51),'mobby',BOLD,CYAN)
    text(d,(70,122),'Android 手机上的本地 Agent',MID,WHITE)
    text(d,(70,178),'从选择 Agent 到执行任务，一路可见。',REG,MUTED)
    steps=[('01','选择 Agent','Codex  ·  Claude Code  ·  OpenCode'),('02','配置网关','地址、模型与密钥保存在设备'),('03','执行任务','查看回复、工具步骤与运行状态')]
    active=min(2,int(seconds//3))
    for i,(number,title,desc) in enumerate(steps):
        y=284+i*126
        fill='#273f55' if i==active else '#1a2841'
        border=(106,int(203+23*pulse),int(190+30*pulse)) if i==active else '#34435d'
        rr(d,(68,y,644,y+98),23,fill,outline=border,width=3 if i==active else 1)
        text(d,(94,y+23),number,MID,CYAN if i==active else '#73849d')
        text(d,(165,y+13),title,REG,WHITE)
        text(d,(165,y+51),desc,SMALL,MUTED)
    # A traveling signal ties the current step to the fictional phone screen.
    d.line((664,336,759,336),fill='#315f69',width=3)
    dot_x=665+int(92*(phase/3))
    d.ellipse((dot_x-7,329,dot_x+7,343),fill=CYAN)
    # Simplified, deliberately fictional phone UI.
    rr(d,(781,26,1186,698),53,'#090e1d',outline='#53627d',width=3)
    rr(d,(796,42,1171,681),40,'#f6f8fb')
    rr(d,(901,48,1067,71),13,'#090e1d')
    text(d,(827,96),'mobby',REG,'#263652')
    if active==0:
        rr(d,(817,172,1150,497),25,'#ffffff',outline='#e1e7ed')
        text(d,(843,196),'选择 Agent',REG,'#1f3049')
        for j,label in enumerate(('Codex','Claude Code','OpenCode')):
            y=254+j*72; rr(d,(839,y,1128,y+57),16,'#eaf7f4' if j==0 else '#f2f5f8')
            text(d,(858,y+11),label,SMALL,'#1c5060' if j==0 else '#53657b')
        d.ellipse((1091-int(3*pulse),273-int(3*pulse),1115+int(3*pulse),297+int(3*pulse)),outline='#118c86',width=3)
    elif active==1:
        rr(d,(817,170,1150,542),25,'#ffffff',outline='#e1e7ed')
        text(d,(841,191),'网关设置',REG,'#1f3049')
        for j,(label,value) in enumerate((('服务','OpenRouter'),('Agent','Codex · OpenCode'),('模型','选择模型'))):
            y=252+j*80;rr(d,(838,y,1128,y+66),15,'#f2f5f8')
            text(d,(850,y+7),label,SMALL,'#69798c');text(d,(850,y+34),value,SMALL,'#1f3049')
        d.ellipse((1095,281,1119,305),fill='#118c86')
        d.ellipse((1102,288,1112,298),fill=WHITE)
        rr(d,(838,510,1128,517),4,'#e4eeec')
        rr(d,(838,510,838+int(290*(phase/3)),517),4,'#118c86')
    else:
        rr(d,(817,169,1150,256),22,'#e8f3f1')
        text(d,(840,190),'检查项目里的测试失败',SMALL,'#263b52')
        rr(d,(817,281,1150,499),23,'#ffffff',outline='#e1e7ed')
        text(d,(840,299),'Codex 正在处理',REG,'#263b52')
        for j,label in enumerate(('✓ 读取测试报告','✓ 检查相关文件','● 修改并验证')):
            text(d,(844,351+j*46),label,SMALL,'#19867f' if j==2 else '#54667b')
        rr(d,(844,474,1118,482),4,'#e2eeec')
        rr(d,(844,474,844+int(274*(0.2+0.8*phase/3)),482),4,'#118c86')
    rr(d,(817,566,1150,635),21,'#ffffff',outline='#d9e3eb')
    text(d,(840,584),'继续提问…',SMALL,'#8290a1')
    d.ellipse((1090,578,1133,621),fill='#118c86');text(d,(1101,583),'↑',REG,WHITE)
    text(d,(69,675),'功能示意动画 · 非真实运行录屏',SMALL,'#8495ae')
    return im


def render_video(ffmpeg):
    fps=15;duration=9
    cmd=[ffmpeg,'-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s','1280x720','-r',str(fps),'-i','-','-an','-c:v','libx264','-pix_fmt','yuv420p','-crf','25','-movflags','+faststart',str(OUT/'walkthrough.mp4')]
    p=subprocess.Popen(cmd,stdin=subprocess.PIPE)
    try:
        for index in range(fps*duration):
            p.stdin.write(frame(index/fps).tobytes())
        p.stdin.close()
        if p.wait()!=0: raise RuntimeError('ffmpeg failed')
    finally:
        if p.poll() is None: p.kill()
    frame(6).save(OUT/'walkthrough-poster.png',optimize=True)
    previews=[frame(i/5).resize((960,540)).quantize(colors=96) for i in range(45)]
    previews[0].save(OUT/'walkthrough.gif',save_all=True,append_images=previews[1:],duration=200,loop=0,optimize=True)


if __name__=='__main__':
    import shutil,sys
    render_svg()
    if len(sys.argv)>1: binary=sys.argv[1]
    else: binary=shutil.which('ffmpeg')
    if not binary: raise SystemExit('Pass an ffmpeg executable to also generate video')
    render_video(binary)
