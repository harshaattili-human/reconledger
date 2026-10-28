"""Summarize all ordinary-table trials without pooling away adverse repetitions."""
import argparse
import gzip
from hashlib import sha256
import json
from pathlib import Path


def summarize(raw):
    report = json.loads(raw)
    if report['status'] != 'completed' or not report['applicationStateUnchanged']:
        raise ValueError('Expected a complete isolated PostgreSQL run')
    trials = report['trials']
    if len(trials) != 64:
        raise ValueError('Expected all 64 trials')
    groups = []
    for tenants in (3, 200):
        for allocation in ('blocked', 'shuffled'):
            for config in ('baseline', 'index', 'index-and-mcv', 'mcv'):
                selected = [t for t in trials if (t['tenants'], t['allocation'], t['configuration'])
                            == (tenants, allocation, config)]
                if {t['repeat'] for t in selected} != set(range(4)) or len(selected) != 4:
                    raise ValueError('Missing or duplicate repetitions')
                group = {'tenants': tenants, 'allocation': allocation, 'configuration': config, 'pages': []}
                for tenant, currency in (('probe-large', 'XTS'), ('probe-large', 'USD'), ('probe-small', 'XTS')):
                    for page in ('first', 'continuation'):
                        observations = [p for t in selected for p in t['pages']
                                        if (p['tenant'], p['currency'], p['page']) == (tenant, currency, page)]
                        if len(observations) != 4 or any(len(p['orderedMs']) != 10 for p in observations):
                            raise ValueError('Incomplete page samples')
                        group['pages'].append({'tenant': tenant, 'currency': currency, 'page': page,
                            'p50MsByRepeat': [p['p50Ms'] for p in observations],
                            'p95MsByRepeat': [p['p95Ms'] for p in observations]})
                for field in ('analyzeMs', 'insert1000Ms', 'postInsertAnalyzeMs'):
                    group[field + 'ByRepeat'] = [t[field] for t in selected]
                groups.append(group)
    return {'rawSha256': sha256(raw).hexdigest(), 'environment': report['environment'],
            'protocol': report['protocol'], 'groups': groups}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    raw = args.report.read_bytes()
    if args.report.suffix == '.gz':
        raw = gzip.decompress(raw)
    print(json.dumps(summarize(raw), indent=2))
