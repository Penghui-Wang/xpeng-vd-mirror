#!/system/bin/sh
# 车机侧资源采样：surfaceflinger CPU、整机 CPU、可用内存、虚拟屏出帧
# 用法: sh /data/local/tmp/sample.sh <秒数> [日志文件]
SEC=${1:-15}
LOG=${2:-/data/local/tmp/vdtok3.log}

SFPID=0
for p in $(ls /proc | grep -E '^[0-9]+$'); do
  c=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
  case "$c" in *surfaceflinger*) SFPID=$p; break;; esac
done
NCORE=$(nproc 2>/dev/null || echo 8)

sf()  { awk '{print $14+$15}' /proc/$SFPID/stat 2>/dev/null; }
cpu() { awk '/^cpu[0-9]/{s+=$2+$3+$4+$5+$6+$7+$8} END{print s+0}' /proc/stat; }
mem() { awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo; }
vd()  { awk '/vdFrames/{gsub(/.*vdFrames=/,"");gsub(/ .*/,"");v=$0} END{print v+0}' "$LOG" 2>/dev/null; }

echo "SFPID=$SFPID CORES=$NCORE memAvail=$(mem)MB"

S0=$(sf); C0=$(cpu); V0=$(vd)
i=1
while [ "$i" -le "$SEC" ]; do
  sleep 1
  S1=$(sf); C1=$(cpu); V1=$(vd); M=$(mem)
  awk -v s="$S1" -v s0="$S0" -v c="$C1" -v c0="$C0" -v n="$NCORE" -v v="$V1" -v v0="$V0" -v m="$M" -v i="$i" \
    'BEGIN{printf "t=%2ds sfCpu=%5.1f%% sysCpu=%5.1f%% vdFrameDelta=%d memAvail=%dMB\n", i, (s-s0)*10*100/1000, (c-c0)*10*100/(1000*n), v-v0, m}'
  S0=$S1; C0=$C1; V0=$V1
  i=$((i+1))
done
