#!/usr/bin/env python3
"""Build an offline SG catalog with unknown roads retained as matching candidates.

OSM tagged maxspeed is MAP_DATA, never silently an official limit. Official sign
corroboration is bounded between two same-value, unambiguously matched signs on
one one-way OSM way, with no intervening junction/zone/conflicting sign.
"""
import argparse,collections,csv,datetime,gzip,hashlib,json,math,re,sqlite3
from pathlib import Path
from shapely.geometry import shape,Point,LineString
from shapely.prepared import prep
from shapely.strtree import STRtree

LTA='https://data.gov.sg/datasets/d_bbf0132c7290d6838f82003972d933d5/view'
RULE='https://sso.agc.gov.sg/SL/RTA1961-N6'
DEFAULT='https://www.lta.gov.sg/content/ltagov/en/getting_around/driving_in_singapore/driving_rules_and_regulations.html'
RULE_CHECKED='2026-11-20'
# Whole-road entries only, confirmed in the indexed current statutory schedules.
# Partial expressway entries tied to unlocated lamp posts are deliberately NOT generalized.
WHOLE={'Kranji Expressway':90,'Adam Road':70,'Ang Mo Kio Avenue 1':70,
       'Aviation Park Road':70,'Bartley Viaduct':70,'Boon Lay Way':70,
       'Braddell Road':70,'Bukit Batok Road':70,'Changi Coast Road':70,'Clementi Avenue 6':70}
EXPRESSWAYS={'PIE':'Pan Island Expressway','BKE':'Bukit Timah Expressway','KJE':'Kranji Expressway',
             'SLE':'Seletar Expressway','TPE':'Tampines Expressway','CTE':'Central Expressway',
             'ECP':'East Coast Parkway','AYE':'Ayer Rajah Expressway','KPE':'Kallang-Paya Lebar Expressway',
             'MCE':'Marina Coastal Expressway'}

def parse_limit(raw):
    m=re.fullmatch(r'(\d{1,3})(?:\s*(?:km/h|kph|kmh))?',str(raw or '').strip().lower())
    return int(m[1]) if m and 5<=int(m[1])<=90 else None
def direction(tags):
    raw=tags.get('oneway:motor_vehicle',tags.get('oneway','')).lower()
    if raw in ('-1','reverse'):return -1
    if raw in ('yes','1','true'):return 1
    if raw in ('no','0','false'):return 0
    if raw in ('reversible','alternating'):return None
    return 1 if tags.get('highway')=='motorway' or tags.get('junction')=='roundabout' else 0
def limits(tags,name):
    forward=parse_limit(tags.get('maxspeed:forward',tags.get('maxspeed:motorcar',tags.get('maxspeed'))))
    backward=parse_limit(tags.get('maxspeed:backward',tags.get('maxspeed:motorcar',tags.get('maxspeed'))))
    mode=direction(tags);reason='No validated explicit maxspeed'
    restricted=any(k.startswith('maxspeed') and ('conditional' in k or 'variable' in k) for k in tags)
    if restricted or mode is None:
        return None,None,None,None,'Conditional/variable speed restriction requires verified applicability'
    if any(tags.get(k)=='no' for k in ('motor_vehicle','motorcar','vehicle','access')):
        return None,None,None,None,'Motor-vehicle access not established'
    if mode==1:backward=None
    if mode==-1:forward=None
    def kind(v):
        if v is None:return None
        rule_current=(datetime.date.today()-datetime.date.fromisoformat(RULE_CHECKED)).days<=90
        if rule_current and name in WHOLE and WHOLE[name]==v and tags.get('tunnel') in (None,'','no') and not tags['highway'].endswith('_link'):
            if name!='Kranji Expressway' or tags['highway']=='motorway':return 'OFFICIAL'
        return 'MAP_DATA'
    fkind,bkind=kind(forward),kind(backward)
    urban=tags.get('maxspeed:type',tags.get('source:maxspeed',''))=='SG:urban' or tags.get('maxspeed')=='SG:urban'
    if urban and tags['highway'] in ('residential','unclassified','service','living_street') and not tags.get('tunnel'):
        if mode!=-1 and forward in (None,50):forward=50;fkind='GENERAL_RULE'
        if mode!=1 and backward in (None,50):backward=50;bkind='GENERAL_RULE'
    return forward,backward,fkind,bkind,reason
def length(a,b):
    return math.hypot((b[0]-a[0])*111195*math.cos(math.radians((a[1]+b[1])/2)),(b[1]-a[1])*111195)

def build(source,output,version):
    source=Path(source);output=Path(output);output.mkdir(parents=True,exist_ok=True)
    audit=json.loads((source/'source-audit.json').read_text())
    assert not audit.get('lta_download_error'),'Keep previous data: official source download failed'
    boundary=json.loads((source/'sg-boundary.json').read_text());country=prep(shape(boundary))
    checked=audit['retrieved_at'][:10]
    ways=[];node_uses=collections.Counter()
    with gzip.open(source/'ways.jsonl.gz','rt') as f:
        for line in f:
            w=json.loads(line);points=w['points'];x,y=points[len(points)//2]
            if not country.covers(Point(x,y)):continue
            t=w['tags'];name=t.get('name:en',t.get('name',''))
            w['name']=name;w['limits']=list(limits(t,name));w['line']=LineString(points)
            ways.append(w);node_uses.update(set(w['nodes']))
    assert len(ways)>10_000
    lines=[w['line'] for w in ways];tree=STRtree(lines)
    with gzip.open(source/'lta-speed-signs.json.gz','rt') as f:signs=json.load(f)['features']
    matches=collections.defaultdict(list);zones=collections.defaultdict(list);sign_stats=collections.Counter()
    for sign in signs:
        p=sign['properties'];label=p.get('TYP_CD') or ''
        maximum=re.match(r'Maximum Speed Limit (\d+)\b',label)
        zone=('school zone' in label.lower() or 'silver zone' in label.lower())
        if not maximum and not zone:continue
        raw=sign['geometry']['coordinates'];point=Point(raw[0],raw[1])
        nearby=sorted((lines[int(i)].distance(point)*111195,int(i)) for i in tree.query(point.buffer(0.00030)))
        if not nearby or nearby[0][0]>18:sign_stats['not_on_unique_road']+=1;continue
        if len(nearby)>1 and nearby[1][0]-nearby[0][0]<8:
            sign_stats['ambiguous_road_or_direction']+=1;continue
        distance,index=nearby[0];way=ways[index]
        along=way['line'].project(point)*111195
        entry={'id':p.get('UNIQUE_ID'),'metres':along,'label':label,'lat':raw[1],'lon':raw[0],'source_updated':p.get('FMEL_UPD_D')}
        if maximum:
            entry['kmh']=int(maximum[1]);matches[index].append(entry);sign_stats['maximum_signs_matched']+=1
        if zone:zones[index].append(entry);sign_stats['zone_markers_matched']+=1
    dbpath=output/'sg-road-speed-limits-875.db'
    if dbpath.exists():dbpath.unlink()
    db=sqlite3.connect(dbpath);db.execute('PRAGMA application_id=1397183308');db.execute('PRAGMA user_version=1')
    db.executescript('''
    PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
    CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
    CREATE TABLE road_way(id INTEGER PRIMARY KEY,name TEXT NOT NULL,highway TEXT NOT NULL,layer INTEGER NOT NULL,
      tunnel INTEGER NOT NULL,bridge INTEGER NOT NULL,forward_allowed INTEGER NOT NULL,backward_allowed INTEGER NOT NULL,
      forward_kmh INTEGER,backward_kmh INTEGER,forward_kind TEXT,backward_kind TEXT,
      forward_source TEXT,backward_source TEXT,checked_date TEXT NOT NULL,restrictions TEXT NOT NULL,reason TEXT NOT NULL);
    CREATE TABLE road_edge(id INTEGER PRIMARY KEY,source_id TEXT NOT NULL UNIQUE,way_id INTEGER NOT NULL REFERENCES road_way(id),
      lat1 REAL NOT NULL,lon1 REAL NOT NULL,lat2 REAL NOT NULL,lon2 REAL NOT NULL,
      verified_kmh INTEGER,verified_source TEXT,verified_date TEXT);
    CREATE TABLE edge_cell(cell INTEGER NOT NULL,edge_id INTEGER NOT NULL REFERENCES road_edge(id),PRIMARY KEY(cell,edge_id)) WITHOUT ROWID;
    ''')
    edge_id=0;known_directions=0;coverage=collections.defaultdict(lambda:collections.Counter())
    intervals=[];unknown_rows=[];provenance=collections.Counter()
    for index,w in enumerate(ways):
        t=w['tags'];name=w['name'];f,b,fk,bk,reason=w['limits'];mode=direction(t)
        limits_here={v for v in (f,b) if v is not None}
        confirmed=sorted(matches[index],key=lambda s:s['metres']);zone=zones[index]
        conflict=any(s['kmh'] not in limits_here for s in confirmed) if limits_here else False
        if conflict:f=b=fk=bk=None;reason='Official sign and mapped maxspeed conflict: check signs'
        if zone and any(v is not None and v>40 for v in (f,b)):
            f=b=fk=bk=None;reason='School/silver zone boundary requires verified applicability'
        try:layer=int(t.get('layer','0'))
        except ValueError:layer=0;f=b=fk=bk=None;reason='Ambiguous mapped road layer'
        forward_allowed=mode!=-1;backward_allowed=mode!=1
        def src(kind):return RULE if kind=='OFFICIAL' else DEFAULT if kind=='GENERAL_RULE' else f"https://www.openstreetmap.org/way/{w['id']}" if kind else None
        restrictions={k:v for k,v in t.items() if k.startswith(('maxspeed','zone:','source:maxspeed')) or k in ('access','vehicle','motor_vehicle')}
        restrictions.update(osm_timestamp=w['timestamp'],lta_signs=confirmed,lta_zone_markers=zone,statutory_rule_checked=RULE_CHECKED)
        db.execute('INSERT INTO road_way VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
          (w['id'],name,t['highway'],layer,int(t.get('tunnel','no') not in ('no','')),int(t.get('bridge','no') not in ('no','')),
           int(forward_allowed),int(backward_allowed),f,b,fk,bk,src(fk),src(bk),checked,json.dumps(restrictions,separators=(',',':')),reason))
        known_directions+=int(f is not None)+int(b is not None)
        provenance[fk or 'UNKNOWN']+=int(forward_allowed);provenance[bk or 'UNKNOWN']+=int(backward_allowed)
        verified=[]
        # Point signs are not road-wide limits. Bound corroboration to a pair on one
        # one-way road, same mapped value, without a branch/zone or conflicting sign.
        if mode in (-1,1) and not zone and not conflict:
            numeric=f if mode==1 else b
            if numeric is not None:
                branch=[]
                for ref,xy in zip(w['nodes'],w['points']):
                    if node_uses[ref]>1:branch.append(w['line'].project(Point(*xy))*111195)
                for a,z in zip(confirmed,confirmed[1:]):
                    if a['kmh']==z['kmh']==numeric and 20<=z['metres']-a['metres']<=1000 and not any(a['metres']+2<x<z['metres']-2 for x in branch):
                        verified.append((a['metres'],z['metres'],numeric));intervals.append({'way_id':w['id'],'name':name,'start':a,'end':z})
        points=list(w['line'].simplify(0.75/111195,preserve_topology=False).coords)
        walked=0.0;way_length=0.0
        for part,(a,z) in enumerate(zip(points,points[1:])):
            n=max(1,math.ceil(length(a,z)/100));last=a
            for sub in range(n):
                now=(a[0]+(z[0]-a[0])*(sub+1)/n,a[1]+(z[1]-a[1])*(sub+1)/n)
                dist=length(last,now)
                if dist<0.5:last=now;continue
                midpoint=Point((last[0]+now[0])/2,(last[1]+now[1])/2)
                if not country.covers(midpoint):last=now;continue
                positions=sorted(w['line'].project(Point(*xy))*111195 for xy in (last,now))
                # A midpoint match alone can incorrectly extend the verified interval.
                official=next((kmh for start,end,kmh in verified
                               if start+1<=positions[0] and positions[-1]<=end-1),None)
                edge_id+=1;source_id=f"{w['id']}:{part}:{sub}"
                db.execute('INSERT INTO road_edge VALUES (?,?,?,?,?,?,?,?,?,?)',
                    (edge_id,source_id,w['id'],last[1],last[0],now[1],now[0],official,LTA if official else None,checked if official else None))
                cells=[]
                for y in range(math.floor(min(last[1],now[1])*1000),math.floor(max(last[1],now[1])*1000)+1):
                    for x in range(math.floor(min(last[0],now[0])*1000),math.floor(max(last[0],now[0])*1000)+1):cells.append((y*1_000_000+x,edge_id))
                db.executemany('INSERT INTO edge_cell VALUES (?,?)',cells)
                group=coverage[t['highway']];group['metres']+=dist*(int(forward_allowed)+int(backward_allowed))
                group['known_metres']+=dist*(int(f is not None)+int(b is not None))
                if official:group['official_sign_metres']+=dist
                way_length+=dist;last=now
        for short,full in EXPRESSWAYS.items():
            if re.sub(r'[^a-z]','',full.lower())==re.sub(r'[^a-z]','',name.lower()) or t.get('ref')==short:
                g=coverage[short];g['ways']+=1;g['metres']+=way_length*(int(forward_allowed)+int(backward_allowed));g['known_metres']+=way_length*(int(f is not None)+int(b is not None))
                if t.get('tunnel') not in (None,'no'):g['tunnel_ways']+=1
        if (forward_allowed and f is None) or (backward_allowed and b is None):unknown_rows.append((w['id'],name,t['highway'],w['points'][0][1],w['points'][0][0],reason))
    info={'version':version,'ways':len(ways),'edges':edge_id,'known_directions':known_directions,'checked_date':checked}
    meta={'schema':'1','version':str(version),'jurisdiction':'SG','boundary':json.dumps(boundary,separators=(',',':')),
          'info':json.dumps(info,separators=(',',':')),'source_audit':json.dumps(audit,separators=(',',':')),
          'licence':'OpenStreetMap contributors ODbL 1.0; Singapore Open Data Licence for LTA signs'}
    db.executemany('INSERT INTO metadata VALUES (?,?)',meta.items());db.commit()
    db.execute('CREATE INDEX edge_way ON road_edge(way_id)');db.execute('VACUUM')
    assert db.execute('PRAGMA quick_check').fetchone()[0]=='ok';assert not db.execute('PRAGMA foreign_key_check').fetchall()
    db.close();size=dbpath.stat().st_size;assert size<=268_435_456
    gzip_path=output/'sg-road-speed-limits-875.db.gz'
    with dbpath.open('rb') as src,gzip.GzipFile(filename=str(gzip_path),mode='wb',mtime=0,compresslevel=9) as gz:
        while chunk:=src.read(65536):gz.write(chunk)
    manifest={'schema':1,'jurisdiction':'SG',**info,'bytes':size,'gzip_bytes':gzip_path.stat().st_size,
              'sha256':hashlib.sha256(dbpath.read_bytes()).hexdigest(),'gzip_sha256':hashlib.sha256(gzip_path.read_bytes()).hexdigest(),
              'osm_source':audit['osm_source'],'official_sign_source':LTA,'statutory_source':RULE}
    (output/'sg-road-speed-limits-875-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    with gzip.open(output/'B875-unknown-road-ways.csv.gz','wt',newline='') as f:
        writer=csv.writer(f);writer.writerow(['osm_way_id','road_name','highway','latitude','longitude','unknown_reason']);writer.writerows(unknown_rows)
    report={'snapshot':info,'direction_provenance':dict(provenance),'coverage':dict(coverage),'official_sign_stats':dict(sign_stats),
            'official_bounded_sign_intervals':len(intervals),'unknown_way_rows':len(unknown_rows),'sources':audit,
            'limitations':['Not 100% road coverage','MAP_DATA is validated mapping, not legal certification',
                          'Conditional/variable or conflicting records remain unknown','Sign bearing semantics not assumed; bounded sign corroboration is one-way only']}
    (output/'B875-data-coverage.json').write_text(json.dumps(report,indent=2)+'\n')
    (output/'B875-official-sign-intervals.json').write_text(json.dumps(intervals,indent=2)+'\n')
    print(json.dumps({'manifest':manifest,'provenance':dict(provenance),'signs':dict(sign_stats),'intervals':len(intervals),'unknown':len(unknown_rows)},indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source');p.add_argument('output');p.add_argument('--version',type=int,required=True)
    a=p.parse_args();build(a.source,a.output,a.version)
