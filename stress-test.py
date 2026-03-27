#!/usr/bin/env python3
"""
Gateway Stress Test Script
Tests rate limiting and Redis failover scenarios
"""

import requests
import time
import threading
import statistics
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict
import argparse

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    BLUE = '\033[94m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

class StressTest:
    def __init__(self, base_url, duration=60, threads=10, route_path="/api/hello"):
        self.base_url = base_url
        self.duration = duration
        self.threads = threads
        self.route_path = route_path
        
        # Statistics
        self.results = defaultdict(int)
        self.latencies = []
        self.errors = []
        self.rate_limited = 0
        self.success = 0
        self.total_requests = 0
        self.lock = threading.Lock()
        
    def send_request(self, request_id):
        """Send a single request and record results"""
        start_time = time.time()
        try:
            # Add API key to bypass auth if needed
            headers = {
                "X-Api-Key": "test-key-123",
                "User-Agent": f"StressTest-{request_id}"
            }
            
            response = requests.get(
                f"{self.base_url}{self.route_path}",
                headers=headers,
                timeout=5
            )
            
            latency = (time.time() - start_time) * 1000  # ms
            
            with self.lock:
                self.total_requests += 1
                self.latencies.append(latency)
                self.results[response.status_code] += 1
                
                if response.status_code == 200:
                    self.success += 1
                elif response.status_code == 429:
                    self.rate_limited += 1
                elif response.status_code == 401:
                    # Auth error - still counts as success for rate limit test
                    self.success += 1
                    
        except requests.exceptions.Timeout:
            with self.lock:
                self.total_requests += 1
                self.errors.append("timeout")
                self.results['timeout'] += 1
        except requests.exceptions.ConnectionError as e:
            with self.lock:
                self.total_requests += 1
                self.errors.append(f"connection: {str(e)[:50]}")
                self.results['connection_error'] += 1
        except Exception as e:
            with self.lock:
                self.total_requests += 1
                self.errors.append(f"error: {str(e)[:50]}")
                self.results['error'] += 1

    def run_test(self):
        """Run the stress test"""
        print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")
        print(f"{Colors.BOLD}Gateway Stress Test{Colors.RESET}")
        print(f"{'='*60}")
        print(f"Target: {self.base_url}{self.route_path}")
        print(f"Duration: {self.duration}s")
        print(f"Threads: {self.threads}")
        print(f"{'='*60}\n")
        
        start_time = time.time()
        end_time = start_time + self.duration
        
        # Progress tracking
        last_report = start_time
        report_interval = 5  # Report every 5 seconds
        
        with ThreadPoolExecutor(max_workers=self.threads) as executor:
            futures = []
            request_id = 0
            
            while time.time() < end_time:
                # Submit requests
                future = executor.submit(self.send_request, request_id)
                futures.append(future)
                request_id += 1
                
                # Small delay to control rate
                time.sleep(0.01)
                
                # Progress report
                if time.time() - last_report >= report_interval:
                    elapsed = time.time() - start_time
                    remaining = self.duration - elapsed
                    with self.lock:
                        rps = self.total_requests / elapsed if elapsed > 0 else 0
                        print(f"[{elapsed:.0f}s/{self.duration}s] "
                              f"Requests: {self.total_requests} | "
                              f"RPS: {rps:.1f} | "
                              f"Success: {Colors.GREEN}{self.success}{Colors.RESET} | "
                              f"Rate Limited: {Colors.YELLOW}{self.rate_limited}{Colors.RESET} | "
                              f"Errors: {Colors.RED}{len(self.errors)}{Colors.RESET}")
                    last_report = time.time()
        
        # Wait for remaining requests
        for future in as_completed(futures):
            pass
        
        return self.get_results()

    def get_results(self):
        """Get test results summary"""
        results = {
            'total_requests': self.total_requests,
            'success': self.success,
            'rate_limited': self.rate_limited,
            'errors': len(self.errors),
            'status_codes': dict(self.results),
            'latency': {
                'min': min(self.latencies) if self.latencies else 0,
                'max': max(self.latencies) if self.latencies else 0,
                'avg': statistics.mean(self.latencies) if self.latencies else 0,
                'p50': statistics.median(self.latencies) if self.latencies else 0,
                'p95': statistics.quantiles(self.latencies, n=20)[18] if len(self.latencies) > 20 else 0,
            } if self.latencies else {}
        }
        return results

    def print_results(self, results):
        """Print formatted results"""
        print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")
        print(f"{Colors.BOLD}Test Results{Colors.RESET}")
        print(f"{'='*60}")
        
        total = results['total_requests']
        print(f"\n{Colors.BLUE}Summary:{Colors.RESET}")
        print(f"  Total Requests: {total}")
        print(f"  Success:        {Colors.GREEN}{results['success']}{Colors.RESET} ({results['success']/total*100:.1f}%)" if total > 0 else "  Success:        0")
        print(f"  Rate Limited:   {Colors.YELLOW}{results['rate_limited']}{Colors.RESET} ({results['rate_limited']/total*100:.1f}%)" if total > 0 else "  Rate Limited:   0")
        print(f"  Errors:         {Colors.RED}{results['errors']}{Colors.RESET}")
        
        print(f"\n{Colors.BLUE}Status Codes:{Colors.RESET}")
        for code, count in sorted(results['status_codes'].items()):
            code_str = str(code)
            if code_str.startswith('2'):
                color = Colors.GREEN
            elif code_str == '429':
                color = Colors.YELLOW
            elif code_str.startswith('4'):
                color = Colors.YELLOW
            else:
                color = Colors.RED
            print(f"  {color}{code}{Colors.RESET}: {count}")
        
        if results['latency']:
            lat = results['latency']
            print(f"\n{Colors.BLUE}Latency (ms):{Colors.RESET}")
            print(f"  Min:  {lat['min']:.1f}")
            print(f"  Max:  {lat['max']:.1f}")
            print(f"  Avg:  {lat['avg']:.1f}")
            print(f"  P50:  {lat['p50']:.1f}")
            print(f"  P95:  {lat['p95']:.1f}")
        
        print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")


def check_redis_status():
    """Check if Redis is running"""
    try:
        import redis
        r = redis.Redis(host='localhost', port=6379)
        r.ping()
        return True
    except:
        return False


def test_rate_limiting():
    """Test rate limiting functionality"""
    print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")
    print(f"{Colors.BOLD}Rate Limiting Test{Colors.RESET}")
    print(f"{'='*60}")
    
    # Check Redis status
    redis_up = check_redis_status()
    print(f"Redis Status: {Colors.GREEN}UP{Colors.RESET}" if redis_up else f"Redis Status: {Colors.RED}DOWN{Colors.RESET}")
    
    # Run test
    test = StressTest(
        base_url="http://localhost",
        duration=30,
        threads=20,
        route_path="/api/hello"
    )
    results = test.run_test()
    test.print_results(results)
    
    return results


def test_redis_failover():
    """Test Redis failover to local rate limiting"""
    print(f"\n{Colors.BOLD}{'='*60}{Colors.RESET}")
    print(f"{Colors.BOLD}Redis Failover Test{Colors.RESET}")
    print(f"{'='*60}")
    print(f"\n{Colors.YELLOW}This test requires manually stopping Redis.{Colors.RESET}")
    print(f"1. Stop Redis: docker stop redis or redis-cli shutdown")
    print(f"2. Run this test to see local rate limiting")
    print(f"3. Start Redis: docker start redis")
    print(f"4. Watch the gradual recovery in logs")
    

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Gateway Stress Test')
    parser.add_argument('--url', default='http://localhost', help='Gateway URL')
    parser.add_argument('--duration', type=int, default=60, help='Test duration in seconds')
    parser.add_argument('--threads', type=int, default=10, help='Number of concurrent threads')
    parser.add_argument('--path', default='/api/hello', help='API path to test')
    parser.add_argument('--mode', choices=['stress', 'ratelimit', 'failover'], 
                       default='stress', help='Test mode')
    
    args = parser.parse_args()
    
    if args.mode == 'ratelimit':
        test_rate_limiting()
    elif args.mode == 'failover':
        test_redis_failover()
    else:
        test = StressTest(
            base_url=args.url,
            duration=args.duration,
            threads=args.threads,
            route_path=args.path
        )
        results = test.run_test()
        test.print_results(results)