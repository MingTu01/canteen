import requests
import json

BASE = "http://localhost:8080/api"

# 1. Admin 登录
resp = requests.post(f"{BASE}/admin/login", json={"username": "admin", "password": "123456"})
token = resp.json().get('data', {}).get('token', '')
print(f"[1] admin/login: code={resp.json().get('code')}, token_len={len(token)}")

if not token:
    print("登录失败，退出")
    exit(1)

headers = {"Authorization": f"Bearer {token}"}

# 2. 查询门店1的员工列表（正确路径：/employee/store/{storeId}）
resp = requests.get(f"{BASE}/employee/store/1", headers=headers)
print(f"[2] employee/store/1: status={resp.status_code}")
data = resp.json()
print(f"    code={data.get('code')}")
emps = data.get('data') or []
if isinstance(emps, list):
    print(f"    count={len(emps)}")
    for e in emps[:5]:
        print(f"    - id={e.get('id')}, name={e.get('name')}, cardNo={e.get('cardNo')}, storeId={e.get('storeId')}, status={e.get('status')}, isDeleted={e.get('isDeleted')}")
else:
    print(f"    data_type={type(emps).__name__}, data={emps}")

# 3. 测试终端员工API（用终端token）
# 先绑定终端获取终端token
resp = requests.post(f"{BASE}/terminal/bind", json={
    "username": "admin",
    "password": "123456",
    "securityCode": "HQ12345678",
    "deviceLabel": "test"
})
print(f"\n[3] terminal/bind: status={resp.status_code}")
tdata = resp.json().get('data') or {}
ttoken = tdata.get('token', '')
tstoreId = tdata.get('storeId')
print(f"    code={resp.json().get('code')}, storeId={tstoreId}, token_len={len(ttoken)}")

if ttoken:
    theaders = {"Authorization": f"Bearer {ttoken}"}
    # 4. 终端员工列表
    resp = requests.get(f"{BASE}/terminal/employees", headers=theaders)
    print(f"\n[4] terminal/employees: status={resp.status_code}")
    tdata = resp.json()
    print(f"    code={tdata.get('code')}")
    temps = tdata.get('data') or []
    print(f"    count={len(temps)}")
    for e in temps[:5]:
        print(f"    - id={e.get('id')}, name={e.get('name')}, cardNo={e.get('cardNo')}")

    # 5. 终端刷卡
    resp = requests.get(f"{BASE}/terminal/employee/CARD001", headers=theaders)
    print(f"\n[5] terminal/employee/CARD001: status={resp.status_code}")
    cdata = resp.json()
    print(f"    code={cdata.get('code')}, message={cdata.get('message')}")
    if cdata.get('data'):
        print(f"    employee: {cdata['data']}")
