#!/usr/bin/env python3
"""Validate a published speed snapshot before replacing the rolling data release."""
import datetime,gzip,hashlib,json,sqlite3,sys
from pathlib import Path

def validate(folder,previous=None):
    folder=Path(folder)
    m=json.loads((folder/'sg-road-speed-limits-875-manifest.json').read_text())
    assert m['schema']==1 and m['jurisdiction']=='SG'
    assert 10_000<m['ways']<=400_000 and 1<=m['edges']<=3_000_000
    assert 10_000<m['known_directions']<=800_000 and m['version']>0
    assert 4096<=m['bytes']<=268_435_456 and 100<=m['gzip_bytes']<=67_108_864
    datetime.date.fromisoformat(m['checked_date'])
    zipped=(folder/'sg-road-speed-limits-875.db.gz').read_bytes()
    assert len(zipped)==m['gzip_bytes'] and hashlib.sha256(zipped).hexdigest()==m['gzip_sha256']
    raw=gzip.decompress(zipped)
    assert len(raw)==m['bytes'] and hashlib.sha256(raw).hexdigest()==m['sha256']
    path=folder/'validated-speed.db';path.write_bytes(raw)
    try:
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as db:
            def scalar(sql):return db.execute(sql).fetchone()[0]
            def meta(k):return db.execute('SELECT value FROM metadata WHERE key=?',(k,)).fetchone()[0]
            assert scalar('PRAGMA application_id')==1397183308 and scalar('PRAGMA user_version')==1
            assert scalar('PRAGMA quick_check')=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall()
            assert scalar("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('view','trigger')")==0
            assert meta('jurisdiction')=='SG' and int(meta('version'))==m['version']
            assert json.loads(meta('boundary'))['type']=='MultiPolygon'
            info={k:m[k] for k in ('version','ways','edges','known_directions','checked_date')}
            assert json.loads(meta('info'))==info
            assert scalar('SELECT COUNT(*) FROM road_way')==m['ways']
            assert scalar('SELECT COUNT(*) FROM road_edge')==m['edges']
            assert scalar('SELECT COUNT(forward_kmh)+COUNT(backward_kmh) FROM road_way')==m['known_directions']
            assert scalar('''SELECT COUNT(*) FROM road_way WHERE forward_allowed NOT IN (0,1) OR backward_allowed NOT IN (0,1)
                OR forward_kmh NOT BETWEEN 5 AND 90 OR backward_kmh NOT BETWEEN 5 AND 90
                OR (forward_allowed=0 AND forward_kmh IS NOT NULL) OR (backward_allowed=0 AND backward_kmh IS NOT NULL)''')==0
            for prefix in ('forward','backward'):
                assert scalar(f'''SELECT COUNT(*) FROM road_way WHERE {prefix}_kmh IS NOT NULL AND
                    ({prefix}_kind IS NULL OR {prefix}_source IS NULL OR
                    {prefix}_kind NOT IN ('OFFICIAL','MAP_DATA','GENERAL_RULE') OR {prefix}_source NOT LIKE 'https://%')''')==0
            assert scalar('''SELECT COUNT(*) FROM road_edge WHERE lat1 NOT BETWEEN 1.1 AND 1.6 OR lat2 NOT BETWEEN 1.1 AND 1.6
                OR lon1 NOT BETWEEN 103.5 AND 104.3 OR lon2 NOT BETWEEN 103.5 AND 104.3''')==0
            assert scalar('''SELECT COUNT(*) FROM road_edge e JOIN road_way w ON w.id=e.way_id WHERE e.verified_kmh IS NOT NULL AND
                (e.verified_kmh NOT BETWEEN 5 AND 90 OR e.verified_source IS NULL OR e.verified_source NOT LIKE 'https://data.gov.sg/%'
                OR e.verified_date IS NULL OR w.forward_allowed+w.backward_allowed!=1 OR e.verified_kmh!=COALESCE(w.forward_kmh,w.backward_kmh))''')==0
        coverage=json.loads((folder/'B875-data-coverage.json').read_text())
        assert coverage['snapshot']==info
        if previous:
            p=json.loads(Path(previous).read_text())
            assert m['version']>p['version'],'Previous or identical version; keep published catalog'
            assert m['ways']>=p['ways']*.80 and m['known_directions']>=p['known_directions']*.70,'Coverage collapse; keep previous catalog'
    finally:path.unlink(missing_ok=True)
    print(json.dumps({'validated_snapshot':info,'sha256':m['sha256'],'previous_snapshot_preserved_on_failure':True},indent=2))
    return m

if __name__=='__main__':validate(*sys.argv[1:])
