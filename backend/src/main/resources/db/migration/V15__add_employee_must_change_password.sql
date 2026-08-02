-- V15: 新增员工"必须修改密码"标志位
-- 用于 H5 首次登录强制改密功能:
--   - 新建员工未指定密码时使用默认密码 12345678,同时设置 must_change_password=1
--   - 员工在 H5 端首次登录后弹窗强制修改密码,修改成功后 must_change_password=0
--   - 管理员也可手动设置此标志强制员工改密

ALTER TABLE employee ADD COLUMN must_change_password INT DEFAULT 0;

-- 已有员工默认设为 0(不需要强制改密,因为他们已经知道自己的密码)
-- 只有新建且使用默认密码的员工才设为 1(由后端 EmployeeService.createEmployee 控制)
