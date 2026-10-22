import urllib.request,xml.etree.ElementTree as ET,json,re,time,concurrent.futures as cf
S,W,N,E=13.280,74.718,13.425,74.867; st=0.025
want=lambda t: (t.get('shop') in ('tobacco','kiosk','e-cigarette','convenience','alcohol','wine','beverages','general')) or (t.get('amenity') in ('bar','pub','biergarten','place_of_worship')) or ('religion' in t and t.get('building') in ('temple','church','mosque','chapel','shrine'))
def tile(b):
  url='https://api.openstreetmap.org/api/0.6/map?bbox=%f,%f,%f,%f'%(b[1],b[0],b[3],b[2])
  for t in range(3):
    try:
      x=urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'DishaApp/1.0'}),timeout=60).read()
      r=ET.fromstring(x); nodes={}; out=[]
      for n in r.iter('node'):
        nodes[n.get('id')]=(float(n.get('lat')),float(n.get('lon')))
        tg={k.get('k'):k.get('v') for k in n.iter('tag')}
        if tg and want(tg): out.append({'type':'node','id':int(n.get('id')),'lat':float(n.get('lat')),'lon':float(n.get('lon')),'tags':tg})
      for wy in r.iter('way'):
        tg={k.get('k'):k.get('v') for k in wy.iter('tag')}
        if tg and want(tg):
          pts=[nodes[nd.get('ref')] for nd in wy.iter('nd') if nd.get('ref') in nodes]
          if pts: out.append({'type':'way','id':int(wy.get('id')),'lat':sum(p[0] for p in pts)/len(pts),'lon':sum(p[1] for p in pts)/len(pts),'tags':tg})
      return b,out,None
    except Exception as e: err=str(e); time.sleep(2)
  return b,[],err
tiles=[]; la=S
while la<N:
  lo=W
  while lo<E: tiles.append((la,lo,min(la+st,N),min(lo+st,E))); lo+=st
  la+=st
els={}; fails=[]
with cf.ThreadPoolExecutor(4) as ex:
  for b,out,err in ex.map(tile,tiles):
    if err: fails.append((b,err))
    for o in out: els[(o['type'],o['id'])]=o
print('tiles',len(tiles),'fails',fails)
json.dump(list(els.values()),open('osm_api.json','w')); print('total',len(els))
