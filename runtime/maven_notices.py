"""Generate offline notices for the actual release dependency graph, including transitives."""
import argparse
import io
import json
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parent.parent
NS = {'p': 'http://maven.apache.org/POM/4.0.0'}


def pom_licenses(cache, group, name, version, seen=None):
    coordinate = f'{group}:{name}:{version}'
    seen = set() if seen is None else seen
    if coordinate in seen:
        raise ValueError('Cyclic license metadata: ' + coordinate)
    seen.add(coordinate)
    directory = cache / group / name / version
    candidates = sorted(directory.rglob('*.pom'))
    if candidates:
        root = ET.parse(candidates[0]).getroot()
    else:
        relative = '/'.join([group.replace('.', '/'), name, version, f'{name}-{version}.pom'])
        repository = 'https://dl.google.com/dl/android/maven2/' if group.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
        with urllib.request.urlopen(repository + relative, timeout=30) as response:
            root = ET.fromstring(response.read(2 * 1024 * 1024))
    licenses = [entry.findtext('p:name', default='', namespaces=NS) for entry in root.findall('p:licenses/p:license', NS)]
    if licenses:
        return licenses
    parent = root.find('p:parent', NS)
    if parent is not None:
        return pom_licenses(cache, *(parent.findtext('p:' + field, namespaces=NS) for field in ('groupId', 'artifactId', 'version')), seen=seen)
    raise ValueError('Missing license metadata: ' + coordinate)


def spdx(names):
    result = []
    for name in names:
        if 'Apache' in name and ('2.0' in name or '2' in name):
            result.append('Apache-2.0')
        elif 'MIT' in name:
            result.append('MIT')
        elif name == 'BSD-2-Clause':
            result.append(name)
        else:
            raise ValueError('Unreviewed Maven license: ' + name)
    return sorted(set(result))


def archive_notices(path):
    documents = []
    def read(stream, prefix=''):
        with zipfile.ZipFile(stream) as archive:
            for entry in archive.infolist():
                leaf = entry.filename.rsplit('/', 1)[-1]
                if re.match(r'(?i)^(LICENSE|NOTICE|COPYING|COPYRIGHT)(\..*|[-_].*)?$', leaf) and not entry.is_dir():
                    if entry.file_size > 2 * 1024 * 1024:
                        raise ValueError('Oversized license notice')
                    text = archive.read(entry).decode('utf-8')
                    documents.append((prefix + entry.filename, text))
                if entry.filename == 'classes.jar':
                    read(io.BytesIO(archive.read(entry)), 'classes.jar/')
    read(path)
    return documents


def generate(artifacts, cache, output):
    approved = json.loads((PROJECT / 'third_party/maven/dependencies.lock.json').read_text())
    rows = []
    groups = {}
    for artifact in artifacts:
        coordinate = ':'.join(artifact[field] for field in ('group', 'name', 'version'))
        groups.setdefault(coordinate, []).append(artifact)
    for coordinate, variants in sorted(groups.items()):
        artifact = variants[0]
        licenses = spdx(pom_licenses(cache, artifact['group'], artifact['name'], artifact['version']))
        if approved.get(coordinate) != licenses:
            raise ValueError('Dependency license must be reviewed: ' + coordinate)
        documents = [document for variant in variants for document in archive_notices(variant['file'])]
        override = {'org.nibor.autolink:autolink:0.12.0': 'autolink-0.12.0-LICENSE',
                    'org.slf4j:slf4j-api:1.7.36': 'slf4j-1.7.36-LICENSE'}.get(coordinate)
        if coordinate == 'com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8':
            documents.extend((name, (PROJECT / 'third_party/onnxruntime' / name).read_text()) for name in ('LICENSE', 'ThirdPartyNotices.txt'))
        if override:
            documents.append((override, (PROJECT / 'third_party/maven' / override).read_text()))
        if 'Apache-2.0' in licenses:
            documents.append(('Apache-2.0', (PROJECT / 'third_party/codex/LICENSE').read_text()))
        if not documents or ('Apache-2.0' not in licenses and not any('Copyright' in text or 'copyright' in text for _, text in documents)):
            raise ValueError('Missing original license/copyright notice: ' + coordinate)
        unique = list(dict.fromkeys(documents))
        rows.append({'id': coordinate, 'title': coordinate, 'license': ', '.join(licenses),
                     'text': '\n\n'.join('--- ' + title + ' ---\n' + text for title, text in unique)})
    if set(approved) != {row['title'] for row in rows}:
        raise ValueError('Dependency graph drift: review the license inventory')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    print('Offline Maven notices:', len(rows))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    generate(json.loads(args.artifacts.read_text()), args.cache, args.output)
