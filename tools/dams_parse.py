import sys
t=open(sys.argv[1],encoding='utf-8',errors='replace').read().splitlines()
print("\n".join(t[:90]))
sys.exit(0)
