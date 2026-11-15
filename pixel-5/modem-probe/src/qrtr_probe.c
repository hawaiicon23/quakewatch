/* Read-only Qualcomm service discovery over the local QRTR control socket.
 * No cellular transmission, QMI configuration, DIAG commands or NV writes.
 * Freestanding AArch64 Linux executable; no Android libc/NDK required.
 * QRTR ABI: include/uapi/linux/qrtr.h in the Linux kernel.
 */
typedef __UINT32_TYPE__ u32;
typedef __UINT16_TYPE__ u16;
typedef __SIZE_TYPE__ usize;
#ifndef LOOKUP_SERVICE
#define LOOKUP_SERVICE 0
#endif

static long call6(long nr, long a, long b, long c, long d, long e, long f) {
    register long x0 __asm__("x0")=a;
    register long x1 __asm__("x1")=b;
    register long x2 __asm__("x2")=c;
    register long x3 __asm__("x3")=d;
    register long x4 __asm__("x4")=e;
    register long x5 __asm__("x5")=f;
    register long x8 __asm__("x8")=nr;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1),"r"(x2),"r"(x3),"r"(x4),"r"(x5),"r"(x8) : "memory","cc");
    return x0;
}

static int out(const char *s) {
    usize n=0; while(s[n]) n++;
    while(n) {
        long wrote=call6(64,1,(long)s,n,0,0,0);
        if(wrote<=0) return -1;
        s+=wrote; n-=wrote;
    }
    return 0;
}

static void number(long n) {
    char s[24]; unsigned i=23;
    s[i]=0;
    unsigned long value=n<0 ? -n : n;
    do { s[--i]='0'+value%10; value/=10; } while(value);
    if(n<0) s[--i]='-';
    out(s+i);
}

static int fail(const char *stage, long error) {
    out("{\"error\":\""); out(stage); out("\",\"linux_errno\":"); number(-error); out("}\n");
    return 1;
}

struct address { u16 family, padding; u32 node, port; };
struct control { u32 command, service, instance, node, port; };
struct timeout { long seconds, micros; };
_Static_assert(sizeof(struct address)==12,"QRTR address size");
_Static_assert(sizeof(struct control)==20,"QRTR control size");

__attribute__((used)) int probe_main(void) {
    long fd=call6(198,42,2,0,0,0,0); /* socket(AF_QIPCRTR, SOCK_DGRAM, 0) */
    if(fd<0) return fail("socket",fd);
    struct address local={0}; u32 local_size=sizeof(local);
    long rc=call6(204,fd,(long)&local,(long)&local_size,0,0,0);
    if(rc<0 || local.family!=42 || local_size!=sizeof(local)) {
        call6(57,fd,0,0,0,0,0);
        return fail("getsockname",rc<0 ? rc : -22);
    }
    struct timeout wait={3,0};
    rc=call6(208,fd,1,20,(long)&wait,sizeof(wait),0); /* SO_RCVTIMEO */
    if(rc<0) { call6(57,fd,0,0,0,0,0); return fail("receive_timeout",rc); }
    struct address nameserver=local;
    nameserver.port=0xfffffffeu;
    struct control request={10,LOOKUP_SERVICE,0,0,0}; /* QRTR_TYPE_NEW_LOOKUP */
    rc=call6(206,fd,(long)&request,sizeof(request),0,(long)&nameserver,sizeof(nameserver));
    if(rc!=sizeof(request)) { call6(57,fd,0,0,0,0,0); return fail("lookup",rc<0 ? rc : -5); }
    unsigned count=0, lte_count=0; int complete=0;
    while(count<512) {
        struct control answer={0}; struct address from={0}; u32 from_size=sizeof(from);
        rc=call6(207,fd,(long)&answer,sizeof(answer),0,(long)&from,(long)&from_size);
        if(rc<0) {
            if(rc!=-11) fail("receive",rc);
            break;
        }
        if(rc!=sizeof(answer) || answer.command!=4 || from.node!=nameserver.node || from.port!=nameserver.port) continue;
        if(!answer.service && !answer.instance && !answer.node && !answer.port) { complete=1; break; }
        count++; if(answer.service==70) lte_count++;
        out("{\"service\":"); number(answer.service);
        out(",\"version\":"); number(answer.instance&255);
        out(",\"instance\":"); number(answer.instance>>8);
        out(",\"node\":"); number(answer.node);
        out(",\"port\":"); number(answer.port); out("}\n");
    }
    call6(57,fd,0,0,0,0,0);
    out("{\"lookup_service\":"); number(LOOKUP_SERVICE);
    out(",\"complete\":"); out(complete ? "true" : "false");
    out(",\"service_count\":"); number(count);
    out(",\"lte_service_70_count\":"); number(lte_count); out("}\n");
    return complete ? 0 : 2;
}

__attribute__((naked,noreturn)) void _start(void) {
    __asm__ volatile("bl probe_main\nmov x8, #93\nsvc #0\n");
}
