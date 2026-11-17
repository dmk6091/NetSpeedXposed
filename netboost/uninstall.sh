#!/system/bin/sh
# put back the values the phone had before Net Boost
D=/data/adb/netboost
if [ -f $D/orig.txt ]; then
  while read -r f v; do [ -w "$f" ] && echo "$v" > "$f" 2>/dev/null; done < $D/orig.txt
fi
rm -rf $D
