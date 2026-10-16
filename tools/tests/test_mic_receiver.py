import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("mic_receiver", Path(__file__).parents[1] / "mic_receiver.py")
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


class JitterBufferTest(unittest.TestCase):
    def drain(self, buffer):
        result = []
        while (item := buffer.pop(flush=True)) is not None:
            result.append(item)
            self.assertLess(len(result), 1100)
        return result

    def test_reorders_before_decode(self):
        buffer = receiver.PacketJitterBuffer()
        for seq in [10, 12, 11]:
            buffer.push(seq, str(seq).encode())
        self.assertEqual([(10, b'10'), (11, b'11'), (12, b'12')], self.drain(buffer))
        self.assertEqual(0, buffer.lost)

    def test_duplicate_and_old_packets_do_not_explode_plc_count(self):
        buffer = receiver.PacketJitterBuffer(1)
        buffer.push(100, b'first')
        self.assertEqual((100, b'first'), buffer.pop())
        for seq in [100, 99, 0xFFFFFFFF]:
            self.assertFalse(buffer.push(seq, b'old'))
        self.assertEqual([], self.drain(buffer))
        self.assertEqual(0, buffer.lost)

    def test_duplicate_pending_packet_keeps_original(self):
        buffer = receiver.PacketJitterBuffer()
        buffer.push(1, b'original')
        self.assertFalse(buffer.push(1, b'duplicate'))
        self.assertEqual([(1, b'original')], self.drain(buffer))

    def test_wraparound_is_forward_progress(self):
        buffer = receiver.PacketJitterBuffer()
        for seq in [0xFFFFFFFE, 0, 0xFFFFFFFF, 1]:
            buffer.push(seq, b'a')
        self.assertEqual([0xFFFFFFFE, 0xFFFFFFFF, 0, 1], [seq for seq, _ in self.drain(buffer)])
        self.assertEqual(0, buffer.lost)

    def test_small_gap_uses_bounded_plc(self):
        buffer = receiver.PacketJitterBuffer()
        buffer.push(1, b'a'); buffer.push(4, b'd')
        self.assertEqual([(1, b'a'), (2, None), (3, None), (4, b'd')], self.drain(buffer))
        self.assertEqual(2, buffer.lost)

    def test_large_jump_resynchronizes(self):
        buffer = receiver.PacketJitterBuffer(1)
        buffer.push(1, b'a'); buffer.pop()
        buffer.push(1000000000, b'b')
        self.assertEqual([(1000000000, b'b')], self.drain(buffer))
        self.assertEqual(1, buffer.resyncs)
        self.assertEqual(0, buffer.lost)

    def test_prebuffer_and_tail_flush(self):
        buffer = receiver.PacketJitterBuffer()
        buffer.push(1, b'a'); buffer.push(2, b'b')
        self.assertIsNone(buffer.pop())
        self.assertEqual(2, len(self.drain(buffer)))

    def test_new_microphone_session_resets_sequence_without_plc(self):
        buffer = receiver.PacketJitterBuffer(1)
        buffer.push(100, b'old'); buffer.pop()
        buffer.reset_session()
        self.assertTrue(buffer.push(0, b'new'))
        self.assertEqual([(0, b'new')], self.drain(buffer))
        self.assertEqual(0, buffer.lost)
        self.assertEqual(1, buffer.resyncs)

    def test_memory_is_bounded_even_if_consumer_stops(self):
        buffer = receiver.PacketJitterBuffer(3, 1000)
        for seq in range(10000):
            buffer.push(seq, b'payload')
            self.assertLessEqual(len(buffer.pending), 64)

    def test_prebuffer_bounds(self):
        for count in [0, 65, -1]:
            with self.assertRaises(ValueError): receiver.PacketJitterBuffer(count)


if __name__ == '__main__':
    unittest.main()
