#!/usr/bin/env python3
"""Build a source-only ZIP with a stable archive root. Does not run Maven or participant code."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def package(root,destination,allow_template=False):
    manifest=json.loads((root/'bot.json').read_text())
    if manifest.get('protocol_version')!='0.1' or manifest.get('language')!='java':raise ValueError('Invalid Java protocol manifest')
    if not manifest.get('bot_name') or not manifest.get('contributors'):raise ValueError('Set your bot name and contributor roster')
    if not allow_template and any('REPLACE' in name for name in manifest['contributors']):raise ValueError('Replace the contributor placeholder in bot.json before submitting')
    for file in ['pom.xml','STRATEGY.md','src/main/java/club/pokermadness/Main.java','src/main/java/club/pokermadness/BotFactory.java']:
        if not (root/file).is_file():raise ValueError('Missing required file: '+file)
    files=[]
    for name in ['src','data','tools','examples','schemas']:
        path=root/name
        if not path.exists():continue
        for file in path.rglob('*'):
            if file.is_symlink():raise ValueError('Symlinks are not accepted: '+str(file))
            if file.is_file() and '__pycache__' not in file.parts and file.suffix!='.pyc':files.append(file)
    for name in ['bot.json','STRATEGY.md','pom.xml','README.md','PROTOCOL.md','DEPENDENCIES.md','.gitignore']:
        if (root/name).is_file():files.append(root/name)
    files=sorted(set(files))
    if len(files)>1000:raise ValueError('Local diagnostic limit: more than 1000 files; contact organizers')
    if sum(f.stat().st_size for f in files)>50*1024*1024:raise ValueError('Local diagnostic limit: more than 50 MiB expanded; contact organizers')
    destination.parent.mkdir(parents=True,exist_ok=True)
    # A changed bot should produce a changed hash; timestamps and platform permissions do not vary.
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as z:
        for file in files:
            info=zipfile.ZipInfo(file.relative_to(root).as_posix(),date_time=(2026,10,16,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
            z.writestr(info,file.read_bytes())
    with zipfile.ZipFile(destination) as z:
        if z.testzip() is not None:raise ValueError('ZIP integrity check failed')
    return len(files),hashlib.sha256(destination.read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,default=ROOT/'dist/submission.zip')
    p.add_argument('--allow-template',action='store_true',help='Only for organizer checks of the unedited starter')
    a=p.parse_args();count,digest=package(ROOT,a.output,a.allow_template)
    print('Wrote',a.output,'with',count,'files');print('SHA-256',digest)
    print('This checks packaging only; run mvn verify and the fixture runner separately.')

if __name__=='__main__':main()
