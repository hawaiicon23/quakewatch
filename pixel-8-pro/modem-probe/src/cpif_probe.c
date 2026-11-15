/* AArch64 Linux CPIF status/version query. No modem payload writes.
 * IOCTL constants verified against Google's s5300 modem_prj.h.
 * This queries the local driver, not PC5/sidelink capability or RF transfer.
 */
typedef __SIZE_TYPE__ usize;
static long call6(long nr,long a,long b,long c,long d,long e,long f) {
    register long x0 __asm__("x0")=a, x1 __asm__("x1")=b;
    register long x2 __asm__("x2")=c, x3 __asm__("x3")=d;
    register long x4 __asm__("x4")=e, x5 __asm__("x5")=f;
    register long x8 __asm__("x8")=nr;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8) : "memory","cc");
    return x0;
}
static void out(const char *s) {
    usize n=0; while(s[n]) n++;
    while(n) { long r=call6(64,1,(long)s,n,0,0,0); if(r<=0) return; s+=r; n-=r; }
}
static void number(long n) {
    char s[24]; unsigned i=23; s[i]=0;
    unsigned long v=n<0 ? -n : n;
    do { s[--i]='0'+v%10; v/=10; } while(v);
    if(n<0) s[--i]='-'; out(s+i);
}
__attribute__((used)) int probe_main(void) {
    /* openat(AT_FDCWD, path, O_RDONLY|O_NONBLOCK|O_CLOEXEC, 0) */
    long fd=call6(56,-100,(long)"/dev/umts_boot0",2048|524288,0,0,0);
    if(fd<0) { out("{\"error\":\"open\",\"linux_errno\":"); number(-fd); out("}\n"); return 1; }
    long state=call6(29,fd,0x6f27,0,0,0,0); /* IOCTL_GET_CP_STATUS */
    char version[21]={0};
    long result=call6(29,fd,0x80146f56,(long)version,0,0,0); /* IOCTL_GET_CPIF_VERSION */
    call6(57,fd,0,0,0,0,0);
    out("{\"cp_status_ioctl_result\":"); number(state);
    out(",\"cpif_version_ioctl_result\":"); number(result);
    out(",\"cpif_version\":\"");
    /* Print only ASCII identifier characters; no unterminated kernel buffer. */
    for(unsigned i=0;i<20 && version[i];i++) {
        char c=version[i];
        if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.') {
            char one[2]={c,0}; out(one);
        }
    }
    out("\"}\n");
    return state>=0 && result==0 ? 0 : 2;
}
__attribute__((naked,noreturn)) void _start(void) {
    __asm__ volatile("bl probe_main\nmov x8, #93\nsvc #0\n");
}
