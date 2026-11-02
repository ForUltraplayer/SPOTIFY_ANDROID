# @file check_translations.py
# @brief Compare a translated values-XX folder with the default strings: missing names, placeholders, escapes and XML.
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# No space flag: "85% or more" is a sentence, not "% o".
PLACEHOLDER = re.compile(r'%(\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdfxXoc%]|\{[a-zA-Z_]+\}')


def entries(folder):
    """name -> (kind, text or {quantity: text} or [items]) for every translatable entry of the folder."""
    out = {}
    for path in sorted(Path(folder).glob('*.xml')):
        try: root = ET.parse(path).getroot()
        except ET.ParseError as error: raise SystemExit(f'{path}: not valid XML: {error}')
        for node in root:
            if node.get('translatable') == 'false': continue
            name = node.get('name')
            if node.tag == 'string': out[name] = ('string', ''.join(node.itertext()), path.name)
            elif node.tag == 'plurals': out[name] = ('plurals', {i.get('quantity'): ''.join(i.itertext()) for i in node}, path.name)
            elif node.tag == 'string-array': out[name] = ('array', [''.join(i.itertext()) for i in node], path.name)
    return out


def placeholders(text):
    # "%d" and "%1$d" are the same single argument; the kinds and their count must match.
    return sorted(re.sub(r'^%\d+\$', '%', m.group(0)) for m in PLACEHOLDER.finditer(text) if m.group(0) != '%%')


def unescaped_quote(raw):
    # aapt refuses an apostrophe that is neither escaped nor inside double quotes.
    return re.search(r"(?<!\\)'", raw) is not None


def main(default, translated, files):
    # No file named: every string file of the default folder (the build's check).
    base = {k: v for k, v in entries(default).items() if not files or v[2] in files}
    done = entries(translated)
    problems = []
    for name, (kind, text, file) in base.items():
        if name not in done: problems.append(f'missing {file}: {name}'); continue
        other = done[name][1]
        if kind == 'string':
            if placeholders(text) != placeholders(other): problems.append(f'placeholders {name}: {placeholders(text)} vs {placeholders(other)}')
        elif kind == 'plurals':
            want = placeholders(text.get('other', ''))
            for quantity, value in other.items():
                if placeholders(value) != want: problems.append(f'placeholders {name}[{quantity}]: {want} vs {placeholders(value)}')
        elif kind == 'array' and len(text) != len(other): problems.append(f'array size {name}: {len(text)} vs {len(other)}')
    for path in Path(translated).glob('*.xml'):
        for i, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            body = re.sub(r'<[^>]+>', '', line)
            if unescaped_quote(body) and not body.strip().startswith('"'): problems.append(f"{path.name}:{i}: unescaped apostrophe")
    extra = sorted(set(done) - set(entries(default)))
    if extra: problems.append('names not in the default strings: ' + ', '.join(extra[:20]))
    print('\n'.join(problems) or f'ok: {len(base)} entries checked')
    return 1 if problems else 0


if __name__ == '__main__':
    # check_translations.py <values> <values-ko> [file.xml ...]
    sys.exit(main(sys.argv[1], sys.argv[2], set(sys.argv[3:])))
