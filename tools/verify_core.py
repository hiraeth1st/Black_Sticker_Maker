#!/usr/bin/env python3
"""Compile the actual Java core and independently decode its output with libwebp/Pillow."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/aziz/stickeratolyesi'

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

with tempfile.TemporaryDirectory() as temp:
    folder = Path(temp)
    for kind in ['lossy', 'lossless']:
        for n, color in enumerate([(220,30,40,255),(20,210,70,255),(20,60,220,255)]):
            im = Image.new('RGBA', (512,512), (0,0,0,0))
            ImageDraw.Draw(im).rectangle((64,64,448,448), fill=color)
            im.save(folder / f'{kind}{n}.webp', lossless=kind=='lossless', quality=80)
    run('java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',folder,
        JAVA/'PackPlanner.java',JAVA/'Webp.java',ROOT/'tools/CoreCheck.java',ROOT/'tools/ParseJava.java')
    run('java','-cp',folder,'com.aziz.stickeratolyesi.CoreCheck',folder)
    for kind in ['lossy','lossless']:
        with Image.open(folder/f'{kind}-animated.webp') as im:
            assert im.n_frames == 3 and im.size == (512,512)
            assert im.info['loop'] == 0
            for n in range(3):
                im.seek(n)
                decoded = im.convert('RGBA')
                assert decoded.getpixel((0,0))[3] == 0
                pixel = decoded.getpixel((256,256))
                assert pixel[n] > 190 and all(pixel[k] < 90 for k in range(3) if k != n)
                with Image.open(folder/f'{kind}-frame{n}.webp') as frame:
                    assert frame.size == (512,512)
                    assert frame.convert('RGBA').getpixel((0,0))[3] == 0
    print('PASS: independent libwebp decoding of lossy/lossless animation, color order, transparency, and standalone frames')
    sources = list((ROOT/'app/src').rglob('*.java'))
    run('java','-cp',folder,'ParseJava',*sources)
    for xml in (ROOT/'app/src').rglob('*.xml'):
        ET.parse(xml)
    print('PASS: Android XML is well formed')
