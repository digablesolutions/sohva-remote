import sys, pathlib
root = pathlib.Path(sys.argv[1])
limits = {"title.txt":30,"short_description.txt":80,"full_description.txt":4000,"video.txt":200}
ok=True
for loc in sorted(p for p in root.iterdir() if p.is_dir()):
    files = [loc/f for f in limits] + sorted((loc/"changelogs").glob("*.txt"))
    for f in files:
        raw=f.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"): print("BOM!",f); ok=False
        t=raw.decode("utf-8").strip()
        lim = 500 if f.parent.name=="changelogs" else limits[f.name]
        flag = "OK" if len(t)<=lim else "OVER"
        if flag!="OK": ok=False
        print(f"{loc.name:6} {str(f.relative_to(loc)):25} {len(t):5} / {lim}  {flag}")
print("ALL OK" if ok else "PROBLEMS")
