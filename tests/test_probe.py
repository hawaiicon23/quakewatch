import pathlib
import random
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT / "linux-app"))
import protocol as p
from bt_probe import Probe


class ProtocolTests(unittest.TestCase):
    def test_persistent_multiplexed_frames_and_endpoints(self):
        with tempfile.TemporaryDirectory() as d:
            src=ROOT/"android-app/src/dev/blx/btprobe"
            sources=[str(src/n) for n in ("ChatProtocol.java","VoiceProtocol.java","DataProtocol.java")]
            subprocess.run(["javac","-d",d,*sources,str(ROOT/"tests/DataProtocolTest.java")],check=True)
            output=subprocess.check_output(["java","-cp",d,"DataProtocolTest"],text=True)
            self.assertIn("DataProtocol OK",output)

    def test_quake_detection_consensus_and_recorded_waveforms(self):
        with tempfile.TemporaryDirectory() as d:
            src=ROOT/"android-app/src/dev/blx/btprobe"
            names=("Protocol.java","QuakeDetector.java","QuakeConsensus.java")
            subprocess.run(["javac","-d",d,*[str(src/n) for n in names],str(ROOT/"tests/QuakeEngineTest.java"),str(ROOT/"tests/QuakeReplay.java")],check=True)
            result=subprocess.check_output(["java","-cp",d,"QuakeEngineTest"],text=True)
            self.assertIn("QuakeEngine OK",result)
            records=sorted((ROOT/"research/earthquake/data").glob("*.at2"))
            self.assertEqual(len(records),3)
            result=subprocess.check_output(["java","-cp",d,"QuakeReplay",*map(str,records)],text=True)
            self.assertEqual(len(result.splitlines()),4)

    def test_parallel_image_delivery_is_bounded(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["javac","-d",d,str(ROOT/"android-app/src/dev/blx/btprobe/ImageFanout.java"),str(ROOT/"tests/ImageFanoutTest.java")],check=True)
            result=subprocess.check_output(["java","-cp",d,"ImageFanoutTest"],text=True)
            self.assertIn("ImageFanout OK",result)

    def test_connected_image_stream_and_fallback(self):
        with tempfile.TemporaryDirectory() as d:
            sources=[ROOT/"android-app/src/dev/blx/btprobe"/name for name in ("ChatProtocol.java","ChatTransfers.java","BulkImageProtocol.java")]
            subprocess.run(["javac","-d",d,*map(str,sources),str(ROOT/"tests/BulkImageProtocolTest.java")],check=True)
            result=subprocess.check_output(["java","-cp",d,"BulkImageProtocolTest"],text=True)
            self.assertIn("BulkImageProtocol OK",result)

    def test_chat_fragmentation_loss_and_reassembly(self):
        with tempfile.TemporaryDirectory() as d:
            sources=[ROOT/"android-app/src/dev/blx/btprobe"/name for name in ("ChatProtocol.java","ChatTransfers.java")]
            subprocess.run(["javac","-d",d,*map(str,sources),str(ROOT/"tests/ChatProtocolTest.java")],check=True)
            result=subprocess.check_output(["java","-cp",d,"ChatProtocolTest"],text=True)
            self.assertIn("ChatProtocol OK",result)

    def test_fast_chat_live_preview_alert_hops_and_signatures(self):
        # BC/2 fast chat, live previews, alert hop limits and signature fragments (MeshService merge).
        android_jar=pathlib.Path("/srv/projects/.toolchains/android/prebuilts/sdk/35/public/android.jar")
        if not android_jar.exists():
            self.skipTest("android.jar not found (run on the Arch build host)")
        with tempfile.TemporaryDirectory() as d:
            src=ROOT/"android-app/src/dev/blx/btprobe"
            sources=[src/n for n in ("Protocol.java","ChatProtocol.java","ChatTransfers.java","SignedAlerts.java","AlertPlayer.java")]
            subprocess.run(["javac","-nowarn","-cp",str(android_jar),"-d",d,*map(str,sources),str(ROOT/"tests/MeshMergeTest.java")],check=True)
            result=subprocess.check_output(["java","-cp",d+":"+str(android_jar),"MeshMergeTest"],text=True)
            self.assertIn("MeshMergeTest OK",result)

    def test_voice_framing_and_test_tone(self):
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["javac","-d",d,str(ROOT/"android-app/src/dev/blx/btprobe/VoiceProtocol.java"),str(ROOT/"tests/VoiceProtocolTest.java")],check=True)
            output=subprocess.check_output(["java","-cp",d,"VoiceProtocolTest"],text=True)
            self.assertIn("VoiceProtocol OK",output)

    def test_maximum_legacy_payload(self):
        raw=p.test_packet(0x12345678,0xDEADBEEF,"123456789012")
        self.assertEqual(len(raw)+4+3,31)  # AD framing/company ID + optional flags.
        decoded=p.decode(raw)
        self.assertEqual((decoded.sender,decoded.message_id,decoded.text),(0x12345678,0xDEADBEEF,"123456789012"))
        self.assertEqual(raw.hex(),"4254010112345678deadbeef313233343536373839303132")

    def test_utf8_limit(self):
        self.assertEqual(p.decode(p.test_packet(1,2,"é"*6)).text,"é"*6)
        for text in ("", "x"*13, "é"*7):
            with self.assertRaises(ValueError): p.test_packet(1,2,text)

    def test_reject_malformed(self):
        valid=p.test_packet(1,2,"TEST")
        for raw in (b"", valid[:11], b"XX"+valid[2:], valid[:2]+b"\x02"+valid[3:], valid[:3]+b"\x04"+valid[4:],valid+b"x"*20,
                    p.HEADER.pack(b"BT",1,p.ACK,1,2),p.HEADER.pack(b"BT",1,p.ACK,1,2)+b"12345"):
            self.assertIsNone(p.decode(raw),raw)

    def test_ack_target(self):
        ack=p.decode(p.ack_packet(0xFFFFFFFF,123,0x87654321))
        self.assertEqual((ack.sender,ack.message_id,ack.recipient),(0xFFFFFFFF,123,0x87654321))

    def test_android_delivery_state(self):
        with tempfile.TemporaryDirectory() as d:
            sources=[ROOT/"android-app/src/dev/blx/btprobe"/name for name in ("Protocol.java","DeliveryTracker.java","PeerStats.java","RelayPolicy.java","PowerPolicy.java")]
            subprocess.run(["javac","-d",d,*map(str,sources),str(ROOT/"tests/DeliveryTrackerTest.java")],check=True)
            result=subprocess.check_output(["java","-cp",d,"DeliveryTrackerTest"],text=True)
            self.assertIn("DeliveryTracker OK",result)

    def test_java_interoperability(self):
        self.assertIsNotNone(shutil.which("javac"),"Run on the Arch build host with its JDK in PATH")
        rng=random.Random(815)
        vectors=[]
        for i in range(200):
            sender=rng.getrandbits(32); message_id=rng.getrandbits(32)
            vectors.append(p.test_packet(sender,message_id,"é"*rng.randint(1,6)) if i%2 else p.ack_packet(sender,message_id,rng.getrandbits(32)))
        vectors += [p.beacon_packet(12,i,"Phone-A") for i in range(20)]
        for hops in range(4):
            vectors += [p.encode(p.RELAY_TEXT,1,123,b"\x00\x00\x00\x03HELLO",hops),p.encode(p.RELAY_ACK,3,123,b"\x00\x00\x00\x01",hops)]
        vectors += [rng.randbytes(i) for i in range(40)]
        with tempfile.TemporaryDirectory() as d:
            subprocess.run(["javac","-d",d,str(ROOT/"android-app/src/dev/blx/btprobe/Protocol.java"),str(ROOT/"tests/ProtocolInterop.java")],check=True)
            output=subprocess.check_output(["java","-cp",d,"ProtocolInterop"],input="\n".join(v.hex() for v in vectors)+"\n",text=True)
        expected=[]
        for raw in vectors:
            packet=p.decode(raw)
            expected.append("invalid" if packet is None else f"{packet.kind} {packet.sender} {packet.message_id} {packet.body.hex()}")
        self.assertEqual(output.splitlines(),expected)


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.events=[]
        self.probe=Probe(self.events.append)
        self.probe.sender=1
        self.probe._pump=lambda: None  # Exercise packet handling without a radio.

    def test_receive_deduplicates_and_replies_once(self):
        raw=p.test_packet(2,123,"TEST")
        for _ in range(30): self.probe.receive(raw,-50)
        self.assertEqual(self.probe.rx,1)
        self.assertEqual(len(self.probe.queue),1)
        reply=p.decode(self.probe.queue[0])
        self.assertEqual((reply.kind,reply.message_id,reply.recipient),(p.ACK,123,2))

    def test_ack_requires_matching_recipient_and_pending_id(self):
        self.probe.pending[123]=time.monotonic()
        self.probe.receive(p.ack_packet(2,123,3))
        self.probe.receive(p.ack_packet(2,999,1))
        self.assertEqual(self.probe.acks,0)
        self.probe.receive(p.ack_packet(2,123,1))
        self.assertEqual(self.probe.acks,1)
        self.assertIn(123,self.probe.pending)

    def test_multiple_peers_can_reply_to_same_broadcast(self):
        self.probe.pending[123]=time.monotonic()
        for sender in (2,3,4,2,3):
            self.probe.receive(p.ack_packet(sender,123,1))
        self.assertEqual(self.probe.acks,3)
        self.probe.receive(p.test_packet(5,123,"OTHER"))
        self.assertEqual(self.probe.rx,1)

    def test_lost_ack_is_repeated_without_duplicate_message(self):
        raw=p.test_packet(2,123,"TEST")
        with patch("bt_probe.time.monotonic", return_value=100): self.probe.receive(raw)
        self.probe.queue.clear()
        with patch("bt_probe.time.monotonic", return_value=101): self.probe.receive(raw)
        self.assertFalse(self.probe.queue)
        with patch("bt_probe.time.monotonic", return_value=103): self.probe.receive(raw)
        self.assertEqual(len(self.probe.queue),1)
        self.assertEqual(self.probe.rx,1)

    def test_retry_is_cancelled_after_ack_or_session_change(self):
        self.probe.running=True
        self.probe.pending[123]=time.monotonic()
        self.probe.attempts[123]=1
        raw=p.test_packet(1,123,"TEST")
        self.probe._retry(raw,self.probe.generation)
        self.assertEqual(len(self.probe.queue),1)
        self.probe.queue.clear()
        self.probe.receive(p.ack_packet(2,123,1))
        self.probe._retry(raw,self.probe.generation)
        self.probe._retry(raw,self.probe.generation-1)
        self.assertFalse(self.probe.queue)

    def test_timeout_drops_queued_retry_and_preserves_other_peers(self):
        self.probe.pending[123]=time.monotonic()
        self.probe.queue.extend([p.test_packet(1,123,"OLD"),p.test_packet(1,124,"NEW"),p.ack_packet(1,123,2)])
        self.probe._timeout(123)
        self.assertEqual(len(self.probe.queue),2)
        self.assertNotIn(123,self.probe.pending)

    def test_own_packets_and_malformed_ignored(self):
        for raw in (b"bad",p.test_packet(1,55,"TEST")):
            self.probe.receive(raw)
        self.assertEqual(self.events,[])

    def test_auto_reply_can_be_disabled(self):
        self.probe.auto_ack=False
        self.probe.receive(p.test_packet(2,3,"TEST"))
        self.assertEqual(self.probe.rx,1)
        self.assertFalse(self.probe.queue)

    def test_duplicate_cache_bounded(self):
        self.probe.auto_ack=False
        for i in range(300): self.probe.receive(p.test_packet(2,i,"TEST"))
        self.assertEqual(len(self.probe.seen),256)

    def test_continuous_beacons_update_signal_without_ack_or_message_spam(self):
        for seq,rssi in ((1,-50),(1,-55),(2,-70)):
            self.probe.receive(p.beacon_packet(22,seq,"Phone-A"),rssi)
        self.assertFalse(self.probe.queue)
        self.assertEqual(self.probe.rx,0)
        self.assertEqual(self.probe.peers[22]["samples"],2)
        self.assertEqual(self.probe.peers[22]["rssi"],-70)
        self.assertEqual(self.probe.peers[22]["name"],"Phone-A")

    def test_continuous_beacon_peers_are_separate_and_bounded(self):
        for sender in range(2,100): self.probe.receive(p.beacon_packet(sender,1,"Peer"),-65)
        self.assertEqual(len(self.probe.peers),64)

    def test_continuous_tick_does_not_build_a_backlog(self):
        self.probe.running=True
        self.probe.continuous=True
        for _ in range(20): self.probe._beacon_tick()
        self.assertEqual(len(self.probe.queue),1)
        self.assertEqual(p.decode(self.probe.queue[0]).kind,p.BEACON)
        self.probe.running=False
        self.assertFalse(self.probe._beacon_tick())

    def test_relay_three_node_delivery_and_destination_ack(self):
        def node(device_id,relay):
            probe=Probe(lambda event:None)
            probe.sender=device_id; probe.running=True; probe.relay_enabled=relay
            probe._pump=lambda:None
            return probe
        a,b,c=node(1,True),node(2,True),node(3,False)
        a.pending[123]=time.monotonic()
        raw=p.relay_packet(1,123,3,"HELLO")
        with patch("bt_probe.GLib.timeout_add",side_effect=lambda ms,callback,*args:callback(*args)):
            b.receive(raw,-60)
            forwarded=b.queue.popleft()
            packet=p.decode(forwarded)
            self.assertEqual((packet.sender,packet.recipient,packet.hops,packet.text),(1,3,2,"HELLO"))
            self.assertEqual(b.rx,0)
            c.receive(forwarded,-70)
            self.assertEqual(c.rx,1)
            c.receive(forwarded,-75)
            self.assertEqual(c.rx,1)
            self.assertEqual(len(c.queue),1)
            ack=c.queue.popleft()
            self.assertEqual(p.decode(ack).sender,3)
            b.receive(ack,-65)
            a.receive(b.queue.popleft(),-60)
            self.assertEqual(a.acks,1)
            b.receive(raw,-60)
            self.assertFalse(b.queue)

    def test_relay_policy_hop_limit_duplicates_and_bounded_retries(self):
        policy=p.RelayPolicy()
        packet=p.decode(p.relay_packet(1,123,3,"HELLO"))
        one=p.decode(policy.forward(packet,0))
        self.assertEqual(one.hops,2)
        self.assertIsNone(policy.forward(packet,1))
        self.assertIsNone(policy.forward(one,6))
        self.assertIsNotNone(policy.forward(packet,6))
        self.assertIsNotNone(policy.forward(packet,12))
        self.assertIsNone(policy.forward(packet,18))
        exhausted=p.decode(p.encode(p.RELAY_TEXT,1,124,packet.body,0))
        self.assertIsNone(policy.forward(exhausted,20))

    def test_relay_disabled_does_not_forward_other_destinations(self):
        self.probe.receive(p.relay_packet(2,123,3,"HELLO"),-50)
        self.assertEqual(self.probe.rx,0)
        self.assertFalse(self.probe.queue)

    def test_relay_payload_limits_and_invalid_hops(self):
        self.assertEqual(len(p.relay_packet(1,2,3,"12345678"))+7,31)
        for text in ("", "123456789", "é"*5):
            with self.assertRaises(ValueError): p.relay_packet(1,2,3,text)
        raw=bytearray(p.relay_packet(1,2,3,"HELLO"));raw[3]=0x44
        self.assertIsNone(p.decode(raw))
        normal=bytearray(p.test_packet(1,2,"TEST"));normal[3]=0x11
        self.assertIsNone(p.decode(normal))


if __name__=="__main__":
    unittest.main()
