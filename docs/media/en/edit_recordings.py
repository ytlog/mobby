"""Trim real recordings, speed up waits, and create README GIFs.

Run: python edit_recordings.py RAW_DIRECTORY --ffmpeg /path/to/ffmpeg
Cut entries in edits.json are [start seconds, end seconds, playback speed].
Requires FFmpeg; no UI frames are generated or replaced.
"""
import argparse,json,subprocess,tempfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('raw_directory',type=Path)
parser.add_argument('--ffmpeg',default='ffmpeg')
args=parser.parse_args()
output=Path(__file__).parent
for spec in json.loads((output/'edits.json').read_text()):
    with tempfile.TemporaryDirectory() as tmp:
        pieces=[]
        for i,(start,end,speed) in enumerate(spec['cuts']):
            piece=Path(tmp)/f'{i}.mp4';pieces.append(piece)
            subprocess.run([args.ffmpeg,'-y','-v','error','-ss',str(start),'-t',str(end-start),'-i',str(args.raw_directory/spec['source']),'-an','-vf',f'setpts=(PTS-STARTPTS)/{speed},scale=540:-2,fps=24','-c:v','libx264','-preset','fast','-crf','22','-pix_fmt','yuv420p',str(piece)],check=True)
        listing=Path(tmp)/'concat.txt';listing.write_text(''.join(f"file '{p}'\n" for p in pieces))
        video=output/(spec['name']+'-edited.mp4')
        subprocess.run([args.ffmpeg,'-y','-v','error','-f','concat','-safe','0','-i',str(listing),'-c','copy','-movflags','+faststart',str(video)],check=True)
        subprocess.run([args.ffmpeg,'-y','-v','error','-i',str(video),'-filter_complex','fps=10,scale=360:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle','-loop','0',str(output/(spec['name']+'.gif'))],check=True)
        print(spec['name'],round(sum((e-s)/v for s,e,v in spec['cuts']),1),'seconds',flush=True)
