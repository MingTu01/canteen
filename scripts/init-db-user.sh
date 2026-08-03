#!/bin/bash
#==============================================================
# P1-3 MySQL 应用专用用户初始化脚本
#==============================================================
# 此脚本由 MySQL 容器的 docker-entrypoint-initdb.d 在首次初始化时执行
# 仅当 DB_APP_USERNAME 和 DB_APP_PASSWORD 环境变量同时设置时才创建用户
#
# 权限策略(最小权限原则):
#   - canteen_app: 仅 SELECT/INSERT/UPDATE/DELETE(运行时 DML)
#   - Flyway 迁移使用 root 账号(通过 SPRING_FLYWAY_USER 配置)
#
# 注意:
#   1. 此脚本仅在 MySQL 数据目录为空时(首次创建)执行
#   2. 对已存在的部署,需手动执行此脚本或用 root 登录 MySQL 创建用户
#   3. 不配置 DB_APP_USERNAME 时,后端默认使用 root(向后兼容)
#==============================================================

# 仅当应用用户名和密码都配置时才创建
if [ -z "$DB_APP_USERNAME" ] || [ -z "$DB_APP_PASSWORD" ]; then
    echo "[init-db-user] DB_APP_USERNAME 或 DB_APP_PASSWORD 未配置,跳过应用用户创建(后端将使用 root)"
    exit 0
fi

echo "[init-db-user] 创建 MySQL 应用专用用户: ${DB_APP_USERNAME}"

# 创建用户(若已存在则更新密码)
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
-- P1-3 创建应用专用用户(最小权限:仅 DML,无 DDL/GRANT)
CREATE USER IF NOT EXISTS '${DB_APP_USERNAME}'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';
ALTER USER '${DB_APP_USERNAME}'@'%' IDENTIFIED BY '${DB_APP_PASSWORD}';

-- 授予 canteen 库的 DML 权限(SELECT/INSERT/UPDATE/DELETE)
-- 不授予 CREATE/ALTER/DROP/INDEX/GRANT(防止应用被攻破后修改表结构)
GRANT SELECT, INSERT, UPDATE, DELETE ON ${MYSQL_DATABASE:-canteen}.* TO '${DB_APP_USERNAME}'@'%';

FLUSH PRIVILEGES;
SQL

if [ $? -eq 0 ]; then
    echo "[init-db-user] 应用用户 ${DB_APP_USERNAME} 创建成功(仅 DML 权限)"
else
    echo "[init-db-user] 警告:应用用户创建失败,后端将回退到 root 账号" >&2
fi
