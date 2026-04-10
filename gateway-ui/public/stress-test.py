#!/usr/bin/env python3
"""
Gateway 压力测试工具
用法: python stress-test.py [--duration 60] [--threads 10] [--url URL]
"""

import argparse
import threading
import time
import requests
from datetime import datetime
from collections import Counter

class StressTest:
    def __init__(self, url, duration, threads):
        self.url = url
        self.duration = duration
        self.threads = threads
        self.results = Counter()
        self.running = True
        self.response_times = []
        self.lock = threading.Lock()

    def send_request(self):
        """发送单个请求"""
        start = time.time()
        try:
            resp = requests.get(self.url, timeout=5)
            elapsed = (time.time() - start) * 1000
            with self.lock:
                self.results['total'] += 1
                if resp.status_code == 200:
                    self.results['success'] += 1
                else:
                    self.results['error'] += 1
                self.response_times.append(elapsed)
        except Exception as e:
            with self.lock:
                self.results['total'] += 1
                self.results['error'] += 1

    def worker(self):
        """工作线程"""
        while self.running:
            self.send_request()

    def run(self):
        """运行压测"""
        print("=" * 50)
        print("   Gateway 压力测试工具")
        print("=" * 50)
        print()
        print(f"测试URL: {self.url}")
        print(f"持续时间: {self.duration} 秒")
        print(f"并发线程: {self.threads}")
        print()
        print("开始压测...")
        print()

        # 启动工作线程
        workers = []
        for i in range(self.threads):
            t = threading.Thread(target=self.worker, daemon=True)
            t.start()
            workers.append(t)

        # 监控进度
        start_time = time.time()
        end_time = start_time + self.duration

        while time.time() < end_time:
            remaining = end_time - time.time()
            elapsed = time.time() - start_time
            progress = (elapsed / self.duration) * 100

            with self.lock:
                total = self.results['total']
                errors = self.results['error']

            print(f"\r[{datetime.now().strftime('%H:%M:%S')}] 进度: {progress:.0f}% | "
                  f"剩余: {remaining:.0f}秒 | 已发送: {total} 请求 | 错误: {errors}", end="")

            time.sleep(0.5)

        # 停止测试
        self.running = False
        time.sleep(1)  # 等待线程结束

        # 计算结果
        actual_duration = time.time() - start_time
        total = self.results['total']
        success = self.results['success']
        errors = self.results['error']

        avg_response = sum(self.response_times) / len(self.response_times) if self.response_times else 0
        qps = total / actual_duration
        error_rate = (errors / total * 100) if total > 0 else 0

        print()
        print()
        print("=" * 50)
        print("   压测完成!")
        print("=" * 50)
        print()
        print("统计结果:")
        print(f"  总请求数:   {total}")
        print(f"  成功请求:   {success}")
        print(f"  失败请求:   {errors}")
        print(f"  错误率:     {error_rate:.2f}%")
        print(f"  持续时间:   {actual_duration:.2f} 秒")
        print(f"  QPS:        {qps:.2f} 请求/秒")
        print(f"  平均响应:   {avg_response:.2f} ms")
        print()
        print("现在可以查看 Prometheus 指标和 AI 分析结果!")
        print()
        print("API调用示例:")
        print(f'  curl -X POST "http://localhost:9090/api/ai/analyze/timerange" -H "Content-Type: application/json" -d \'{{"provider":"BAILIAN","language":"zh"}}\'')
        print()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Gateway压力测试工具')
    parser.add_argument('--duration', type=int, default=60, help='压测持续时间(秒)')
    parser.add_argument('--threads', type=int, default=10, help='并发线程数')
    parser.add_argument('--url', type=str, default='http://localhost:9090/api/email/status', help='测试URL')

    args = parser.parse_args()

    test = StressTest(args.url, args.duration, args.threads)
    test.run()