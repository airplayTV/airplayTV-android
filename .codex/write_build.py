import os
path = os.path.join("D:\\repo\\github.com\\airplayTV\\airplayTV-android\\app\\build.gradle.kts")
with open(path, "w", encoding="utf-8") as f:
    f.write('test123')
print("written ok")
