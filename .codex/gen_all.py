import os, sys
ROOT = r"D:\repo\github.com\airplayTV\airplayTV-android"
Q = chr(34)
def q(s): return s.replace(chr(96), Q)
def write_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, chr(119)+chr(105)+chr(116)+chr(104), encoding=chr(117)+chr(116)+chr(102)+chr(45)+chr(56)) as f:
        f.write(q(content))
    print(chr(79)+chr(75)+chr(58)+chr(32)+path.split(chr(92))[-1])
