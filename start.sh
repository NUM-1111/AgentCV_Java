#!/usr/bin/env bash
# ============================================================
# AgentCV — 一键启动脚本（前后端）
# 用法：bash start.sh
# ============================================================
set -e

# -------------------- 颜色定义 --------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN}   AgentCV — 简历智能优化系统  一键启动${NC}"
echo -e "${CYAN}============================================================${NC}"
echo ""

# -------------------- 项目根目录 --------------------
cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

# -------------------- 1. 环境变量检查 --------------------
if [ ! -f "${PROJECT_DIR}/.env" ]; then
    echo -e "${YELLOW}[1/4] 未检测到 .env 文件，正在从 .env.example 创建...${NC}"
    cp "${PROJECT_DIR}/.env.example" "${PROJECT_DIR}/.env"
    echo -e "${GREEN}  已创建 .env，请编辑此文件填入 API Key 等信息后重新运行。${NC}"
    echo -e "${YELLOW}  文件位置：${PROJECT_DIR}/.env${NC}"
    exit 1
fi

echo -e "${GREEN}[1/4] .env 文件已就绪 ✓${NC}"

# -------------------- 2. 加载环境变量 --------------------
set -a
source "${PROJECT_DIR}/.env"
set +a

# 检查必要变量
if [ -z "$OPENAI_API_KEY" ] || [ "$OPENAI_API_KEY" = "sk-your-key-here" ]; then
    echo -e "${RED}[错误] 请在 .env 中配置正确的 OPENAI_API_KEY${NC}"
    exit 1
fi

echo -e "${GREEN}[2/4] 环境变量已加载 ✓${NC}"
echo "   API Base: ${OPENAI_BASE_URL:-https://api.openai.com/v1}"
echo "   Model:    ${OPENAI_MODEL_NAME:-gpt-4o-mini}"
echo "   Port:     ${SERVER_PORT:-8081}"

# -------------------- 3. Maven 检查 --------------------
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}[错误] 未检测到 mvn 命令，请先安装 Maven。${NC}"
    exit 1
fi

echo -e "${GREEN}[3/4] Maven 已就绪 ✓${NC}"

# -------------------- 端口占用检测 --------------------
PORT=${SERVER_PORT:-8081}
if lsof -Pi :"$PORT" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo ""
    echo -e "${YELLOW}============================================================${NC}"
    echo -e "${YELLOW}  端口 $PORT 已被占用，应用可能已在运行中${NC}"
    echo -e "${YELLOW}  前端页面：http://localhost:$PORT${NC}"
    echo -e "${YELLOW}  如需重启请先停止：kill \$(lsof -t -i:$PORT)${NC}"
    echo -e "${YELLOW}============================================================${NC}"
    exit 0
fi

# -------------------- 4. 编译并启动 --------------------
echo -e "${GREEN}[4/4] 正在编译并启动 Spring Boot 应用...${NC}"
echo ""

# 使用 Maven 启动（spring-boot:run），环境变量已通过 source .env 加载
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=${SERVER_PORT:-8081}" &
APP_PID=$!

# -------------------- 5. 等待启动完成 --------------------
echo ""
echo -e "${CYAN}等待应用启动...${NC}"
for i in $(seq 1 60); do
    if curl -s -o /dev/null http://localhost:${SERVER_PORT:-8081}/ 2>/dev/null; then
        echo ""
        echo -e "${GREEN}============================================================${NC}"
        echo -e "${GREEN}  启动成功！${NC}"
        echo -e "${GREEN}  前端页面：http://localhost:${SERVER_PORT:-8081}${NC}"
        echo -e "${GREEN}  进程 PID：$APP_PID${NC}"
        echo -e "${GREEN}  停止命令：kill $APP_PID${NC}"
        echo -e "${GREEN}============================================================${NC}"
        echo ""
        # 保持前端日志输出，按 Ctrl+C 可停止
        wait $APP_PID
        exit 0
    fi
    sleep 1
done

echo -e "${RED}[错误] 应用启动超时，请检查日志。${NC}"
tail -30 /tmp/agentcv.log 2>/dev/null || echo "无日志可显示"
kill $APP_PID 2>/dev/null
exit 1
