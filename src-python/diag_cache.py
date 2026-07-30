"""
诊断脚本:启动 EXE,注入 JS 检查 IndexedDB 是否可用、缓存是否写入。
通过 console.log 输出到 EXE 的 stdout。
诊断完成后自动关闭 EXE。
"""
import subprocess
import time
import urllib.request
import json
import os
import threading

EXE_PATH = r'C:\canteen-terminal\canteen-terminal.exe'
BASE_URL = 'http://127.0.0.1:1287'
STDOUT_LOG = r'C:\canteen-terminal\diag_stdout.txt'
DATA_DIR = r'C:\canteen-terminal\data'

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
    """发送 eval_js 请求"""
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
    # 清空旧日志
    open(STDOUT_LOG, 'w').close()

    print(f'[Diag] 启动 EXE,stdout 重定向到: {STDOUT_LOG}')
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

        print('[Diag] 服务器已就绪,等待前端加载(4秒)...')
        time.sleep(4)

        # 诊断 1:基础环境信息
        print('\n[Diag] === 诊断 1: 基础环境 ===')
        js1 = """JSON.stringify({
            indexedDB: typeof indexedDB,
            localStorage: typeof localStorage,
            pythonShell: window.__pythonShell === true,
            origin: window.location.origin,
            href: window.location.href,
            ls_keys: Object.keys(localStorage),
            terminal_cfg: localStorage.getItem('terminal_config_v2'),
        })"""
        eval_js(js1)
        time.sleep(1)

        # 诊断 2:IndexedDB 写入测试(用 IIFE + setTimeout 确保异步完成)
        print('\n[Diag] === 诊断 2: IndexedDB 写入测试 ===')
        js2 = """(function(){
            if (typeof indexedDB === 'undefined') { console.log('[IDB] indexedDB undefined'); return; }
            try {
                var req = indexedDB.open('diag_test_db', 1);
                req.onupgradeneeded = function() {
                    var db = req.result;
                    if (!db.objectStoreNames.contains('test')) db.createObjectStore('test');
                    console.log('[IDB] onupgradeneeded, stores=' + Array.from(db.objectStoreNames));
                };
                req.onsuccess = function() {
                    var db = req.result;
                    console.log('[IDB] open ok, name=' + db.name + ' stores=' + Array.from(db.objectStoreNames));
                    var tx = db.transaction('test', 'readwrite');
                    var store = tx.objectStore('test');
                    store.put('hello-from-diag', 'test_key');
                    tx.oncomplete = function() { console.log('[IDB] write ok'); db.close(); };
                    tx.onerror = function() { console.log('[IDB] write error: ' + (tx.error && tx.error.message)); db.close(); };
                    tx.onabort = function() { console.log('[IDB] write aborted: ' + (tx.error && tx.error.message)); db.close(); };
                };
                req.onerror = function() { console.log('[IDB] open error: ' + (req.error && req.error.message) + ' name=' + (req.error && req.error.name)); };
                req.onblocked = function() { console.log('[IDB] open blocked'); };
            } catch(e) {
                console.log('[IDB] exception: ' + e.message + ' name=' + e.name);
            }
        })();"""
        eval_js(js2)
        time.sleep(2)

        # 诊断 3:检查 canteen_terminal 数据库(应用使用的)
        print('\n[Diag] === 诊断 3: 检查 canteen_terminal 数据库 ===')
        js3 = """(function(){
            if (typeof indexedDB === 'undefined') { console.log('[App] indexedDB undefined'); return; }
            try {
                var req = indexedDB.open('canteen_terminal', 1);
                req.onupgradeneeded = function() {
                    console.log('[App] canteen_terminal 需要升级(首次创建)');
                    var db = req.result;
                    if (!db.objectStoreNames.contains('dishes')) db.createObjectStore('dishes', {keyPath: 'id'});
                    if (!db.objectStoreNames.contains('images')) db.createObjectStore('images');
                    if (!db.objectStoreNames.contains('menus')) db.createObjectStore('menus');
                };
                req.onsuccess = function() {
                    var db = req.result;
                    console.log('[App] canteen_terminal 打开成功, stores=' + Array.from(db.objectStoreNames));
                    // 统计各 store 记录数
                    var stores = Array.from(db.objectStoreNames);
                    var pending = stores.length;
                    if (pending === 0) { console.log('[App] 无 objectStore'); db.close(); return; }
                    stores.forEach(function(name) {
                        try {
                            var tx = db.transaction(name, 'readonly');
                            var s = tx.objectStore(name);
                            var countReq = s.count();
                            countReq.onsuccess = function() {
                                console.log('[App] store ' + name + ' 记录数=' + countReq.result);
                                pending--;
                                if (pending === 0) db.close();
                            };
                            countReq.onerror = function() {
                                console.log('[App] store ' + name + ' count error');
                                pending--;
                                if (pending === 0) db.close();
                            };
                        } catch(e) {
                            console.log('[App] store ' + name + ' 异常: ' + e.message);
                            pending--;
                            if (pending === 0) db.close();
                        }
                    });
                };
                req.onerror = function() { console.log('[App] canteen_terminal open error: ' + (req.error && req.error.message)); };
            } catch(e) {
                console.log('[App] exception: ' + e.message);
            }
        })();"""
        eval_js(js3)
        time.sleep(2)

    finally:
        print('\n[Diag] 关闭 EXE...')
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
        stdout_f.close()

    # 读取捕获的 stdout
    print(f'\n[Diag] === EXE stdout 日志 ===')
    try:
        with open(STDOUT_LOG, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
            # 过滤出关键日志行
            for line in content.splitlines():
                if any(k in line for k in ['[IDB]', '[App]', '[EvalJS]', '[Init]', '[Server]', '[Window]', 'Error', 'error']):
                    print(f'  {line}')
    except Exception as e:
        print(f'  读取日志失败: {e}')

    # 检查 data 目录
    data_dir = os.path.join(DATA_DIR, 'IndexedDB')
    print(f'\n[Diag] === IndexedDB 目录: {data_dir} ===')
    if os.path.exists(data_dir):
        items = os.listdir(data_dir)
        print(f'  共 {len(items)} 项: {items}')
        for item in items:
            item_path = os.path.join(data_dir, item)
            if os.path.isdir(item_path):
                sub_items = os.listdir(item_path)
                print(f'    {item}/ ({len(sub_items)} 文件)')
    else:
        print('  目录不存在')

if __name__ == '__main__':
    main()
