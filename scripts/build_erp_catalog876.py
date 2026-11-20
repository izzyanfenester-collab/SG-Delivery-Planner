#!/usr/bin/env python3
"""Import the supplied worksheet losslessly; fail the whole build on any bad row."""
import collections,decimal,hashlib,json,re,shutil,sqlite3,sys,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

NS={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
EXPECTED_GROUPS={frozenset(v) for v in [('627','838'),('645','839'),('646','840'),('705','717'),('757','331')]}

def read_workbook(path):
    with zipfile.ZipFile(path) as z:
        wb=ET.fromstring(z.read('xl/workbook.xml'))
        sheets=wb.find('m:sheets',NS)
        sheet=next(s for s in sheets if s.attrib['name']=='ERP_Locations')
        rid=sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']
        rels=ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
        target=next(r.attrib['Target'] for r in rels if r.attrib['Id']==rid)
        target=target.lstrip('/') if target.startswith('/') else 'xl/'+target
        shared=[]
        if 'xl/sharedStrings.xml' in z.namelist():
            shared=[''.join(s.itertext()) for s in ET.fromstring(z.read('xl/sharedStrings.xml'))]
        def value(cell):
            if cell is None:return ''
            if cell.find('m:f',NS) is not None:raise ValueError('Unexpected formula: '+cell.attrib['r'])
            if cell.attrib.get('t')=='inlineStr':return ''.join(n.text or '' for n in cell.findall('.//m:t',NS))
            raw=cell.findtext('m:v','',NS)
            return shared[int(raw)] if cell.attrib.get('t')=='s' else raw
        rows=[]
        for row in ET.fromstring(z.read(target)).findall('m:sheetData/m:row',NS):
            cells={re.sub(r'\d','',c.attrib['r']):c for c in row}
            values=[value(cells.get(k)) for k in 'ABCDE']
            if int(row.attrib['r'])==1:
                assert values==['Unique_ID','Gantry_No','Latitude','Longitude','Google_Maps'],values
                continue
            if not any(values):continue
            uid,gantry,lat,lon,url=values
            assert uid and uid.strip()==uid and re.fullmatch(r'\d+',uid),values
            la,lo=decimal.Decimal(lat),decimal.Decimal(lon)
            assert la.is_finite() and lo.is_finite() and decimal.Decimal('1.1')<=la<=decimal.Decimal('1.6') and decimal.Decimal('103.5')<=lo<=decimal.Decimal('104.3'),values
            assert url==f'https://maps.google.com/?q={lat},{lon}',values
            rows.append({'unique_id':uid,'gantry_no':gantry,'latitude':lat,'longitude':lon,'google_maps':url,'source_row':int(row.attrib['r'])})
        assert len(rows)==106 and len({r['unique_id'] for r in rows})==106
        return rows

def group(rows):
    sites=collections.defaultdict(list)
    for r in rows:
        key=','.join(format(decimal.Decimal(r[c]).normalize(),'f') for c in ('latitude','longitude'))
        sites[key].append(r)
    duplicates={frozenset(r['unique_id'] for r in records) for records in sites.values() if len(records)>1}
    assert len(sites)==101 and duplicates==EXPECTED_GROUPS,duplicates
    return sites

def build(workbook,reference,output):
    workbook=Path(workbook);reference=Path(reference);out=Path(output);out.mkdir(parents=True,exist_ok=True)
    rows=read_workbook(workbook);sites=group(rows)
    sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
    version='xlsx-'+sha(workbook)[:12]
    dbfile=out/'sg_erp_locations_876.db'
    dbfile.unlink(missing_ok=True)
    with sqlite3.connect(dbfile) as db:
        db.execute('PRAGMA application_id='+str(0x45525036));db.execute('PRAGMA user_version=1')
        db.executescript('''
          CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE erp_record(unique_id TEXT PRIMARY KEY,gantry_no TEXT NOT NULL,latitude_text TEXT NOT NULL,longitude_text TEXT NOT NULL,
            latitude REAL NOT NULL,longitude REAL NOT NULL,google_maps TEXT NOT NULL,source_row INTEGER NOT NULL UNIQUE);
          CREATE TABLE erp_site(site_key TEXT PRIMARY KEY,latitude_text TEXT NOT NULL,longitude_text TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL);
          CREATE TABLE erp_site_record(site_key TEXT NOT NULL REFERENCES erp_site(site_key),unique_id TEXT NOT NULL UNIQUE REFERENCES erp_record(unique_id),PRIMARY KEY(site_key,unique_id));
        ''')
        metadata={'schema':'1','version':version,'source_file':workbook.name,'source_sheet':'ERP_Locations',
            'workbook_sha256':sha(workbook),'reference_sha256':sha(reference),'record_count':'106','location_count':'101'}
        db.executemany('INSERT INTO metadata VALUES (?,?)',metadata.items())
        for r in rows:db.execute('INSERT INTO erp_record VALUES (?,?,?,?,?,?,?,?)',(r['unique_id'],r['gantry_no'],r['latitude'],r['longitude'],float(r['latitude']),float(r['longitude']),r['google_maps'],r['source_row']))
        for key,records in sites.items():
            r=records[0]
            db.execute('INSERT INTO erp_site VALUES (?,?,?,?,?)',(key,r['latitude'],r['longitude'],float(r['latitude']),float(r['longitude'])))
            db.executemany('INSERT INTO erp_site_record VALUES (?,?)',[(key,r['unique_id']) for r in records])
        assert db.execute('PRAGMA quick_check').fetchone()[0]=='ok'
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
    shutil.copyfile(workbook,out/'erp_source_876.xlsx');shutil.copyfile(reference,out/'erp_reference_876.jpg')
    manifest={'schema':1,'version':version,'record_count':len(rows),'location_count':len(sites),
        'workbook_sha256':sha(workbook),'reference_sha256':sha(reference),'database_sha256':sha(dbfile),
        'duplicate_groups':[sorted(r['unique_id'] for r in s) for s in sites.values() if len(s)>1],
        'blank_gantry_count':sum(not r['gantry_no'].strip() for r in rows),
        'empty_gantry_count':sum(not r['gantry_no'] for r in rows),
        'whitespace_gantry_count':sum(bool(r['gantry_no']) and not r['gantry_no'].strip() for r in rows),
        'unk_gantry_count':sum(r['gantry_no']=='UNK' for r in rows),
        'coordinate_precision':'Original XML numeric text preserved; REAL doubles are render coordinates only',
        'icon_reference':'Original supplied blue-and-white rectangular E|R|P image, unchanged bytes'}
    (out/'sg_erp_manifest_876.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(manifest,indent=2))
    return rows,sites

if __name__=='__main__':build(*sys.argv[1:4])
