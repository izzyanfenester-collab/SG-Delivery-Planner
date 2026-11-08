#!/usr/bin/env python3
"""Crop Geofabrik Shortbread MBTiles to Singapore before PMTiles conversion."""
import argparse, json, math, sqlite3
from pathlib import Path

def x(lon, zoom):
    return max(0,min((1<<zoom)-1,int((lon+180)/360*(1<<zoom))))
def y(lat, zoom):
    lat=math.radians(max(-85.0511,min(85.0511,lat)))
    return max(0,min((1<<zoom)-1,int((1-math.asinh(math.tan(lat))/math.pi)/2*(1<<zoom))))

def crop(source, target, max_zoom):
    original=sqlite3.connect(f"file:{source}?mode=ro",uri=True)
    dest=sqlite3.connect(target)
    dest.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    dest.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    dest.execute("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level,tile_column,tile_row)")
    items=dict(original.execute("SELECT name,value FROM metadata").fetchall())
    native_max=int(items.get("maxzoom","14"))
    max_zoom=min(max_zoom,native_max)
    count=0
    lon_min,lat_min,lon_max,lat_max=103.57,1.15,104.16,1.49
    for zoom in range(max_zoom+1):
        x1,x2=x(lon_min,zoom),x(lon_max,zoom)
        y1,y2=y(lat_max,zoom),y(lat_min,zoom)
        tms_low=(1<<zoom)-1-y2
        tms_high=(1<<zoom)-1-y1
        rows=original.execute(
            "SELECT zoom_level,tile_column,tile_row,tile_data FROM tiles "
            "WHERE zoom_level=? AND tile_column BETWEEN ? AND ? "
            "AND tile_row BETWEEN ? AND ?",
            (zoom,x1,x2,tms_low,tms_high)
        )
        dest.executemany("INSERT INTO tiles VALUES (?,?,?,?)",rows)
        count+=dest.execute("SELECT changes()").fetchone()[0]
    assert dest.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]>=100, "Singapore vector tiles missing."
    items["name"]="SG Delivery Singapore Offline Shortbread"
    items["bounds"]="103.57,1.15,104.16,1.49"
    items["center"]="103.8198,1.3521,12"
    items["minzoom"]="0"
    items["maxzoom"]=str(max_zoom)
    dest.executemany("INSERT INTO metadata VALUES (?,?)",items.items())
    dest.commit()
    count=dest.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
    dest.close()
    original.close()
    print("Singapore tiles",count,"maxzoom",max_zoom,Path(target).stat().st_size,"bytes")

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--source",required=True)
    p.add_argument("--output",required=True)
    p.add_argument("--max-zoom",type=int,default=15)
    args=p.parse_args()
    crop(args.source,args.output,args.max_zoom)
