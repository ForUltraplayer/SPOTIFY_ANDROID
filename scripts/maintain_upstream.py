# @file maintain_upstream.py
# @brief Detect and report Myspoti updates, and prepare, verify and promote isolated merge candidates.
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
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


# Build output and machine-local files in the candidate are not source.
BUILD_DIRS = {'build', '.gradle', '.kotlin', '.idea'}


def added_files(root, known):
    """Source files created in the candidate after prepare (a new translation file): they join the inspected set."""
    known = set(known)
    found = []
    for folder, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in BUILD_DIRS]
        for name in names:
            rel = Path(folder, name).relative_to(root).as_posix()
            if rel not in known and name != 'local.properties': found.append(rel)
    return sorted(found)


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


def merge(tag):
    """Merge an upstream tag into HEAD without touching the checkout: (upstream commit, HEAD, tree, conflicted paths)."""
    data = release()
    git('fetch', '--no-tags', 'https://github.com/'+data['upstream']+'.git', 'refs/tags/'+tag)
    revision = git('rev-parse', 'FETCH_HEAD^{commit}').stdout.decode().strip()
    head = git('rev-parse', 'HEAD').stdout.decode().strip()
    result = git('merge-tree', '--write-tree', '--name-only', head, revision, check=False)
    if result.returncode not in (0, 1): raise ValueError(result.stderr.decode(errors='replace'))
    lines = result.stdout.decode().splitlines()
    conflicts = []
    for line in lines[1:]:
        if not line: break
        conflicts.append(line)
    return revision, head, lines[0], conflicts


def prepare(tag):
    clean()
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-beta\.\d+)?', tag): raise ValueError('Use an exact upstream release tag')
    revision, head, tree, conflicts = merge(tag)
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
    added = added_files(root, state['files'])
    if added:
        state['files'] += added
        state.setdefault('modes', {}).update({name: '100644' for name in added})
        STATE.write_text(json.dumps(state, indent=2)+'\n', encoding='utf-8')
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


def gh(*args):
    return subprocess.run(['gh', *args], cwd=ROOT, check=True, capture_output=True, text=True, encoding='utf-8').stdout


def fence(text, limit):
    """Upstream text goes in a code fence, so an @name notifies nobody and #123 links nothing here."""
    text = (text or '').strip() or '(없음)'
    if len(text) > limit: text = text[:limit]+'\n…(이하 생략)'
    tick = '`' * max(3, max(map(len, re.findall(r'`+', text)), default=0)+1)
    return tick+'text\n'+text+'\n'+tick


def report(row, get=api, merged=merge):
    """(title prefix, title, body) of the change issue for one upstream channel that moved past our base."""
    data = release()
    repo, channel = data['upstream'], {'stable': '정식', 'beta': '베타'}[row['channel']]
    compare = get(repo+'/compare/'+data['upstreamCommit']+'...'+row['tag'])
    commits, files = compare.get('commits', []), compare.get('files', [])
    conflicts = merged(row['tag'])[3]
    subjects = [c['commit']['message'].split('\n', 1)[0] for c in reversed(commits)][:60]
    text = ('**Myspoti '+channel+'** '+data.get('upstreamTag', data['upstreamCommit'][:7])+' → '+row['tag']+'\n\n'
            '| 항목 | 내용 |\n| --- | --- |\n| 원본 | '+row['url']+' |\n'
            '| 비교 | https://github.com/'+repo+'/compare/'+data['upstreamCommit']+'...'+row['tag']+' |\n'
            '| 규모 | 커밋 '+str(compare['ahead_by'])+'개, 바뀐 파일 '+str(len(files))+('개 이상' if len(files) >= 300 else '개')+' |\n'
            '| 충돌 예상 | 우리 소스와 병합하면 '+str(len(conflicts))+'개 파일 |\n'
            '| 반영 | 자동으로 반영하지 않는다. 허가하면 이 버전에 우리 패치를 다시 붙이고 충돌을 해결한 뒤 검사와 휴대폰 확인을 거쳐 배포한다. |\n'
            '\n### 원본 릴리스 노트\n\n'+fence(get(repo+'/releases/tags/'+row['tag']).get('body'), 6000)+'\n'
            '\n### 충돌 예상 파일\n\n'+fence('\n'.join(conflicts), 4000)+'\n'
            '\n### 커밋 (최신순, '+str(compare['ahead_by'])+'개 중 '+str(len(subjects))+'개)\n\n'+fence('\n'.join(subjects), 8000)+'\n'
            '\n### 바뀐 파일 (최대 100개)\n\n'+fence('\n'.join(f['filename'] for f in files[:100]), 6000)+'\n')
    return '원본 업데이트: Myspoti '+channel+' ', '원본 업데이트: Myspoti '+channel+' '+row['tag'], text


def publish(prefix, title, text, run=gh):
    """Create the issue, or move this channel's open issue to the new release with a comment so it notifies."""
    repo = os.environ.get('GITHUB_REPOSITORY', release()['updateRepository'])
    issues = json.loads(run('issue', 'list', '--repo', repo, '--label', 'upstream', '--state', 'all', '--limit', '300', '--json', 'number,title,state'))
    # A release already reported, open or closed, is not reported again.
    if any(issue['title'] == title for issue in issues): return 'known'
    with tempfile.NamedTemporaryFile('w', suffix='.md', delete=False, encoding='utf-8') as file: file.write(text)
    try:
        current = next((issue for issue in issues if issue['state'] == 'OPEN' and issue['title'].startswith(prefix)), None)
        if current:
            run('issue', 'edit', str(current['number']), '--repo', repo, '--title', title, '--body-file', file.name)
            run('issue', 'comment', str(current['number']), '--repo', repo, '--body', title.removeprefix('원본 업데이트: ')+'(으)로 갱신했다.')
            return 'updated'
        run('issue', 'create', '--repo', repo, '--title', title, '--label', 'upstream', '--body-file', file.name)
        return 'created'
    finally: os.unlink(file.name)


def notify():
    rows = [row for row in detect()['releases'] if row['comparison'] == 'ahead' and row['commits']]
    if rows: gh('label', 'create', 'upstream', '--repo', os.environ.get('GITHUB_REPOSITORY', release()['updateRepository']),
                '--color', '0E8A16', '--description', '원본 변경 알림', '--force')
    for row in rows:
        prefix, title, text = report(row)
        print(title+': '+publish(prefix, title, text))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('check', 'report', 'prepare', 'verify', 'promote'))
    parser.add_argument('--tag')
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--android-sdk', default=os.environ.get('ANDROID_HOME'))
    args = parser.parse_args()
    if args.action == 'report': notify()
    elif args.action == 'prepare': prepare(args.tag or '')
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
