#!/bin/bash
#==============================================================
# 企业智慧食堂系统 - 一键部署脚本
#==============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "  企业智慧食堂系统 - 一键部署"
echo "=========================================="
echo ""

# 检查 Docker 环境
if ! command -v docker &> /dev/null; then
    echo "[错误] 未检测到 Docker，请先安装 Docker。"
    exit 1
fi

if ! docker compose version &> /dev/null; then
    echo "[错误] 未检测到 Docker Compose，请先安装 Docker Compose。"
    exit 1
fi

# 创建必要目录
mkdir -p backup logs

# 选择部署模式
echo "请选择部署模式："
echo "  1) 完整部署（后端 + 管理端 + H5订餐端 + 终端）"
echo "  2) 仅后端 + 管理端"
echo "  3) 自定义（手动编辑 docker-compose.yml）"
read -p "请输入选项 [1]: " deploy_mode
deploy_mode=${deploy_mode:-1}

case $deploy_mode in
    1)
        echo "[信息] 开始完整部署..."
        docker compose up -d --build
        ;;
    2)
        echo "[信息] 部署后端 + 管理端..."
        docker compose up -d --build mysql redis backend admin-web
        ;;
    3)
        echo "[信息] 请手动编辑 docker-compose.yml 后运行 docker compose up -d"
        exit 0
        ;;
    *)
        echo "[错误] 无效选项"
        exit 1
        ;;
esac

echo ""
echo "=========================================="
echo "  部署完成！"
echo "=========================================="
echo ""
echo "服务访问地址："
echo "  管理后台:   http://localhost"
echo "  H5订餐端:   http://localhost:81"
echo "  X86终端:    http://localhost:82"
echo "  后端API:    http://localhost:8080"
echo ""
echo "默认管理员账号："
echo "  超级管理员: admin / 123456"
echo "  门店管理员: store1 / 123456"
echo ""
echo "常用命令："
echo "  查看日志:   docker compose logs -f"
echo "  停止服务:   docker compose down"
echo "  重启服务:   docker compose restart"
echo "  备份数据:   ./scripts/backup.sh"
echo "  恢复数据:   ./scripts/restore.sh"
echo ""
