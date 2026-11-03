#!/usr/bin/env python3
"""Exercise trusted local bots against scripted requests. This is not a poker engine or sandbox."""
import argparse
import json
import math
import queue
import subprocess
import threading
from pathlib import Path

MAX_RESPONSE=131072
MAX_STATE=65536

def strict_json(raw):
    def pairs(items):
        out={}
        for key,value in items:
            if key in out:raise ValueError('Duplicate JSON key: '+key)
            out[key]=value
        return out
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda x: (_ for _ in ()).throw(ValueError('Non-finite JSON number')))

def encoded(value):return json.dumps(value,ensure_ascii=False,allow_nan=False,separators=(',',':')).encode('utf-8')

def state_check(state):
    if not isinstance(state,dict) or len(encoded(state))>MAX_STATE:raise ValueError('user_state must be an object within 65536 bytes')
    def visit(v,depth=0):
        if isinstance(v,(dict,list)):
            if depth>=63:raise ValueError('State is too deeply nested')
            for child in (v.values() if isinstance(v,dict) else v):visit(child,depth+1)
        elif isinstance(v,int) and not isinstance(v,bool) and abs(v)>9007199254740991:raise ValueError('Unsafe state integer')
        elif isinstance(v,float) and not math.isfinite(v):raise ValueError('Non-finite state number')
    visit(state)

def validate_response(request,response):
    if not isinstance(response,dict):raise ValueError('Response must be an object')
    kind={'init':'ready','act':'action','hand_end':'ack'}[request['type']]
    expected={'protocol_version','request_id','type','user_state'}|({'action'} if kind=='action' else set())
    if set(response)!=expected:raise ValueError('Response has missing or extra fields')
    if response['protocol_version']!='0.1' or response['request_id']!=request['request_id'] or response['type']!=kind:raise ValueError('Response correlation/version/type mismatch')
    state_check(response['user_state'])
    if kind=='ready' and response['user_state']:raise ValueError('Ready state must be empty')
    if kind!='action':return
    action=response['action']
    if not isinstance(action,dict):raise ValueError('Action must be an object')
    name=action.get('action')
    legal=next((a for a in request['observation']['legal_actions'] if a['action']==name),None)
    if legal is None:raise ValueError('Unavailable action')
    if name in ('bet','raise'):
        amount=action.get('amount')
        if set(action)!={'action','amount'} or type(amount) is not int or not legal['min_amount']<=amount<=legal['max_amount']:raise ValueError('Illegal target amount')
    elif set(action)!={'action'}:raise ValueError('Check/call/fold must not include an amount')

class Worker:
    def __init__(self,jar):
        # Avoid host JVM performance-data warnings appearing on the protocol stdout stream.
        self.process=subprocess.Popen(['java','-XX:-UsePerfData','-jar',str(jar)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        self.lines=queue.Queue(maxsize=4);self.errors=bytearray()
        def stdout():
            while True:
                line=self.process.stdout.readline(MAX_RESPONSE+2)
                if not line:self.lines.put(None);return
                self.lines.put(line)
                if len(line)>MAX_RESPONSE+1 or not line.endswith(b'\n'):return
        def stderr():
            while True:
                data=self.process.stderr.read(1024)
                if not data:return
                if len(self.errors)<8192:self.errors.extend(data[:8192-len(self.errors)])
        threading.Thread(target=stdout,daemon=True).start();threading.Thread(target=stderr,daemon=True).start()

    def exchange(self,request,timeout):
        self.process.stdin.write(encoded(request)+b'\n');self.process.stdin.flush()
        try:raw=self.lines.get(timeout=timeout)
        except queue.Empty:raise RuntimeError('Response timeout')
        if raw is None:raise RuntimeError('Worker exited without a response: '+self.errors.decode('utf-8',errors='replace'))
        if not raw.endswith(b'\n') or len(raw.rstrip(b'\r\n'))>MAX_RESPONSE:raise ValueError('Oversized or unterminated response')
        response=strict_json(raw.decode('utf-8',errors='strict'));validate_response(request,response);return response

    def close(self):
        try:
            self.process.stdin.close()
            self.process.wait(timeout=2)
        except (BrokenPipeError,subprocess.TimeoutExpired):
            self.process.kill();self.process.wait(timeout=2)
        if self.process.returncode!=0:raise RuntimeError('Worker exited with status '+str(self.process.returncode))
        if not self.lines.empty() and self.lines.get() is not None:raise RuntimeError('Worker emitted unsolicited output')

def main():
    root=Path(__file__).resolve().parents[1]
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,default=root/'target/poker-bot.jar')
    parser.add_argument('--timeout',type=float,default=3,help='Local diagnostic timeout per act/hand_end in seconds; not a tournament limit')
    parser.add_argument('--carry-state',action='store_true',help='Demonstrate explicit state passing across independent fixture snapshots; not a simulated poker session')
    args=parser.parse_args()
    if not args.jar.is_file():parser.error('Build first with mvn verify')
    worker=Worker(args.jar.resolve());state={};passed=0
    try:
        for case in json.loads((root/'examples/cases.json').read_text()):
            request=strict_json((root/'examples'/case['file']).read_text())
            if args.carry_state and request['type']!='init':request['user_state']=state
            response=worker.exchange(request,10 if request['type']=='init' else args.timeout)
            if request['type']!='init':state=response['user_state']
            print('PASS',case['name'],json.dumps(response.get('action',response['type']),separators=(',',':')))
            passed+=1
        worker.close()
    except Exception:
        worker.process.kill();worker.process.wait(timeout=2);raise
    print(str(passed)+' fixtures passed. This checks compatibility, not poker strength.')
    if args.carry_state:print('Final explicitly carried state:',json.dumps(state,sort_keys=True))

if __name__=='__main__':main()
