# @file maintain_upstream.py
# @author: ForUltraplayer
# @brief Detect Myspoti updates and prepare, verify and promote isolated merge candidates.
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / 'work/upstream-candidate.json'


def git(*args, check=True, env=None, input=None):
    return subprocess.run(['git', *args], cwd=ROOT, check=check, input=input,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)


def api(path):
    headers = {'User-Agent': 'Myspoti-Hangul-maintenance', 'Accept': 'application/vnd.github+json'}
    if os.environ.get('GH_TOKEN'): headers['Authorization'] = 'Bearer '+os.environ['GH_TOKEN']
    with urllib.request.urlopen(urllib.request.Request('https://api.github.com/repos/'+path, headers=headers), timeout=30) as r:
        return json.load(r)


def release(root=ROOT):
    return json.loads((root / 'release.json').read_text(encoding='utf-8-sig'))


def clean():
    # Compare content rather than stat-cache hints; Windows checkout line endings
    # can temporarily appear modified after applying an otherwise identical tree.
    changed = git('diff', '--quiet', check=False).returncode or git('diff', '--cached', '--quiet', check=False).returncode
    if changed or git('ls-files', '--others', '--exclude-standard').stdout.strip():
        raise ValueError('Commit or preserve the current source changes before preparing/promoting a merge')


def identity(root):
    data = release(root)
    original = release()
    source = (root / 'base/app/build.gradle.kts').read_text(encoding='utf-8')
    expected = 'applicationId = "'+original['applicationId']+'"'
    if data['applicationId'] != original['applicationId'] or expected not in source:
        raise ValueError('The candidate changes the installed package identity')
    if data['updateRepository'] != original['updateRepository'] or data['versionCode'] < original['versionCode']:
        raise ValueError('The candidate changes the update repository or lowers versionCode')
    for marker in ('MYSPOTI_KEYSTORE', 'MYSPOTI_KEY_ALIAS', 'signingConfig = signingConfigs.getByName'):
        if marker not in source: raise ValueError('The candidate changes the release signing contract')


def candidate_hash(state):
    root = Path(state['directory'])
    digest = hashlib.sha256()
    for name in sorted(state['files']):
        digest.update(name.encode())
        path = root / name
        digest.update(path.read_bytes() if path.exists() else b'<deleted>')
    return digest.hexdigest()


def detect(get=api):
    data = release()
    repo = data['upstream']
    rows = []
    stable = get(repo+'/releases/latest')
    releases = get(repo+'/releases?per_page=20')
    beta = next((r for r in releases if r['prerelease'] and not r['draft']), None)
    for channel, item in [('stable', stable), ('beta', beta)]:
        if not item: continue
        comparison = get(repo+'/compare/'+data['upstreamCommit']+'...'+item['tag_name'])
        rows.append({'channel': channel, 'tag': item['tag_name'], 'url': item['html_url'],
                     'comparison': comparison['status'], 'commits': comparison['ahead_by'],
                     'changed_files': len(comparison.get('files', []))})
    return {'checked_at': datetime.now(timezone.utc).isoformat(), 'base': data['upstreamCommit'], 'releases': rows}


def prepare(tag):
    clean()
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta\.\d+)?', tag): raise ValueError('Use an exact upstream release tag')
    data = release()
    git('fetch', '--no-tags', 'https://github.com/'+data['upstream']+'.git', 'refs/tags/'+tag)
    revision = git('rev-parse', 'FETCH_HEAD^{commit}').stdout.decode().strip()
    head = git('rev-parse', 'HEAD').stdout.decode().strip()
    merge = git('merge-tree', '--write-tree', '--name-only', head, revision, check=False)
    if merge.returncode not in (0, 1): raise ValueError(merge.stderr.decode(errors='replace'))
    lines = merge.stdout.decode().splitlines()
    tree = lines[0]
    conflicts = []
    for line in lines[1:]:
        if not line: break
        conflicts.append(line)
    area = ROOT / 'work' / ('upstream-'+tag+'-'+datetime.now().strftime('%Y%m%d%H%M%S'))
    area.mkdir(parents=True)
    archive = area / 'source.zip'
    git('archive', '--format=zip', '-o', str(archive), tree)
    destination = area / 'source'
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            path = (destination / entry.filename).resolve()
            if not path.is_relative_to(destination.resolve()): raise ValueError('Archive path escapes candidate')
            if entry.is_dir(): path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(bundle.read(entry))
    files = git('ls-tree', '-r', '--name-only', tree).stdout.decode().splitlines()
    modes = dict(line.split(' ', 1)[::-1] for line in git('ls-tree', '-r', '--format=%(objectmode) %(path)', tree).stdout.decode().splitlines())
    # Upstream coding-agent policies are not application source and must not replace this project's policy.
    for name in ('AGENTS.md', 'CLAUDE.md', '.cursorrules'):
        if name in files:
            old = git('show', head+':'+name, check=False)
            if old.returncode == 0: (destination/name).write_bytes(old.stdout)
            else: (destination/name).unlink(missing_ok=True)
            conflicts = [p for p in conflicts if p != name]
    candidate_release = release(destination)
    candidate_release.update(upstreamCommit=revision, upstreamTag=tag)
    (destination/'release.json').write_text(json.dumps(candidate_release, indent=2)+'\n', encoding='utf-8')
    state = {'head': head, 'upstream': revision, 'tag': tag, 'tree': tree,
             'directory': str(destination), 'files': files, 'modes': modes, 'conflicts': conflicts}
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'candidate': str(destination), 'conflicts': conflicts, 'working_source_changed': False}))


def verify(args):
    state = json.loads(STATE.read_text())
    root = Path(state['directory'])
    identity(root)
    for name in state['files']:
        path = root / name
        if not path.exists(): continue
        text = path.read_bytes()
        if re.search(rb'(?m)^(<<<<<<< |>>>>>>> )', text): raise ValueError('Resolve the merge conflict: '+name)
    before = candidate_hash(state)
    log = ROOT / 'work/upstream-verification.log'
    with log.open('w', encoding='utf-8') as output:
        command = ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(root/'build-hangul.ps1'),
                   '-JavaHome', args.java_home, '-AndroidSdk', args.android_sdk, '-TestsOnly', '-FullCompatibility']
        subprocess.run(command, cwd=root, check=True, stdout=output, stderr=subprocess.STDOUT, timeout=900)
    if before != candidate_hash(state): raise ValueError('Candidate changed during inspection')
    proof = {'result': 'passed', 'head': state['head'], 'candidate': before,
             'upstream': state['upstream'], 'device_validation': 'pending'}
    (ROOT/'work/upstream-proof.json').write_text(json.dumps(proof, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(proof))


def promote():
    clean()
    state = json.loads(STATE.read_text())
    proof = json.loads((ROOT/'work/upstream-proof.json').read_text())
    if proof.get('result') != 'passed' or proof.get('head') != git('rev-parse', 'HEAD').stdout.decode().strip() or proof.get('candidate') != candidate_hash(state):
        raise ValueError('Candidate or baseline changed after inspection')
    identity(Path(state['directory']))
    index = ROOT/'work/upstream-index'
    index.unlink(missing_ok=True)
    env = dict(os.environ, GIT_INDEX_FILE=str(index))
    git('read-tree', state['tree'], env=env)
    for name in state['files']:
        path = Path(state['directory']) / name
        if not path.exists(): git('update-index', '--force-remove', '--', name, env=env)
        else:
            blob = git('hash-object', '-w', '--stdin', input=path.read_bytes()).stdout.decode().strip()
            git('update-index', '--add', '--cacheinfo', state['modes'][name], blob, name, env=env)
    tree = git('write-tree', env=env).stdout.decode().strip()
    patch = git('diff', '--binary', state['head'], tree).stdout
    git('apply', '--check', input=patch)
    git('apply', input=patch)
    print('Verified candidate applied to the source checkout. Device validation and release signing remain required.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('check', 'prepare', 'verify', 'promote'))
    parser.add_argument('--tag')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--android-sdk', default=os.environ.get('ANDROID_HOME'))
    args = parser.parse_args()
    if args.action == 'prepare': prepare(args.tag or '')
    elif args.action == 'verify': verify(args)
    elif args.action == 'promote': promote()
    else:
        result = detect()
        path = ROOT/'work/upstream-status.json'; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(result))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                for row in result['releases']:
                    summary.write('- '+row['channel']+': '+row['tag']+' / '+row['comparison']+' / '+str(row['commits'])+' commits\n')


if __name__ == '__main__': main()
