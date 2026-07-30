"""
诊断脚本:检查菜品 image 字段格式。
用两步法:先注入 JS 异步写结果到 window.__diagResult,再同步读回。
"""
import subprocess
import time
import urllib.request
import json
import os

EXE_PATH = r'd:\文档\enterprise-canteen\enterprise-canteen\src-python\dist\canteen-terminal.exe'
BASE_URL = 'http://127.0.0.1:1287'
STDOUT_LOG = r'd:\文档\enterprise-canteen\enterprise-canteen\src-python\dist\diag_stdout.txt'

def wait_for_server(timeout=15):
    start = time.time()
    while time.time() - start < timeout:
        try:
            urllib.request.urlopen(f'{BASE_URL}/__api__/config', timeout=1)
            return True
        except Exception:
            time.sleep(0.5)
    return False

def eval_js(js_code):
    data = json.dumps({'js': js_code}).encode('utf-8')
    req = urllib.request.Request(
        f'{BASE_URL}/__api__/eval_js',
        data=data,
        headers={'Content-Type': 'application/json'},
        method='POST',
    )
    try:
        r = urllib.request.urlopen(req, timeout=5)
        return json.loads(r.read().decode('utf-8'))
    except Exception as e:
        return {'error': str(e)}

def main():
    open(STDOUT_LOG, 'w').close()
    print(f'[Diag] 启动 EXE...')
    stdout_f = open(STDOUT_LOG, 'w', encoding='utf-8', buffering=1)
    proc = subprocess.Popen(
        [EXE_PATH],
        stdout=stdout_f,
        stderr=subprocess.STDOUT,
        cwd=os.path.dirname(EXE_PATH),
    )

    try:
        print('[Diag] 等待服务器就绪...')
        if not wait_for_server():
            print('[Diag] 服务器未就绪')
            return

        print('[Diag] 等待前端加载和缓存初始化(8秒)...')
        time.sleep(8)

        # 步骤 1:异步查询菜品 image 字段,结果写到 window.__diagResult
        print('\n[Diag] === 步骤 1: 异步查询菜品 image 字段 ===')
        js_trigger = """(function(){
            window.__diagResult = null;
            try {
                var req = indexedDB.open('canteen_terminal', 1);
                req.onsuccess = function() {
                    var db = req.result;
                    if (!db.objectStoreNames.contains('dishes')) {
                        window.__diagResult = JSON.stringify({error: 'no dishes store'});
                        db.close();
                        return;
                    }
                    var tx = db.transaction('dishes', 'readonly');
                    var store = tx.objectStore('dishes');
                    var getAllReq = store.getAll();
                    getAllReq.onsuccess = function() {
                        var dishes = getAllReq.result || [];
                        var stats = {
                            total: dishes.length,
                            with_image: 0,
                            without_image: 0,
                            starts_with_uploads: 0,
                            starts_with_http: 0,
                            other_format: 0,
                            samples: [],
                        };
                        dishes.forEach(function(d) {
                            if (!d.image) { stats.without_image++; return; }
                            stats.with_image++;
                            if (d.image.startsWith('/uploads/')) stats.starts_with_uploads++;
                            else if (d.image.startsWith('http')) stats.starts_with_http++;
                            else stats.other_format++;
                            if (stats.samples.length < 5) {
                                stats.samples.push({id: d.id, name: d.name, image: d.image});
                            }
                        });
                        window.__diagResult = JSON.stringify(stats);
                        db.close();
                    };
                };
            } catch(e) {
                window.__diagResult = JSON.stringify({exception: e.message});
            }
        })();"""
        eval_js(js_trigger)
        print('[Diag] 等待异步查询完成(2秒)...')
        time.sleep(2)

        # 步骤 2:同步读取结果
        print('\n[Diag] === 步骤 2: 读取结果 ===')
        eval_js('window.__diagResult')
        time.sleep(1)

        # 同时检查 images store 的内容
        print('\n[Diag] === 步骤 3: 检查 images store ===')
        js_images = """(function(){
            window.__diagImages = null;
            try {
                var req = indexedDB.open('canteen_terminal', 1);
                req.onsuccess = function() {
                    var db = req.result;
                    if (!db.objectStoreNames.contains('images')) {
                        window.__diagImages = JSON.stringify({error: 'no images store'});
                        db.close();
                        return;
                    }
                    var tx = db.transaction('images', 'readonly');
                    var store = tx.objectStore('images');
                    var countReq = store.count();
                    countReq.onsuccess = function() {
                        // 获取所有 key
                        var keysReq = store.getAllKeys();
                        keysReq.onsuccess = function() {
                            window.__diagImages = JSON.stringify({
                                count: countReq.result,
                                keys: (keysReq.result || []).slice(0, 5),
                            });
                            db.close();
                        };
                    };
                };
            } catch(e) {
                window.__diagImages = JSON.stringify({exception: e.message});
            }
        })();"""
        eval_js(js_images)
        time.sleep(2)

        # 读取
        print('\n[Diag] === 读取 images 结果 ===')
        eval_js('window.__diagImages')
        time.sleep(1)

    finally:
        print('\n[Diag] 关闭 EXE...')
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
        stdout_f.close()

    print(f'\n[Diag] === EXE stdout 日志(关键行) ===')
    try:
        with open(STDOUT_LOG, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
            for line in content.splitlines():
                if any(k in line for k in ['[EvalJS Result]', '[cache]', 'image', 'uploads', 'Error', 'stats', 'count', 'samples']):
                    print(f'  {line}')
    except Exception as e:
        print(f'  读取日志失败: {e}')

if __name__ == '__main__':
    main()
