-- 充值记录表新增 remark(备注)列
-- 原先 operator 列被错用为备注存储(单人充值存 remark,批量充值存"批量充值(admin#id)"),
-- 现拆分:operator 存操作人姓名,remark 存备注(如"批量充值"/"余额充值"/用户输入)
ALTER TABLE recharge_record ADD COLUMN remark VARCHAR(255) NULL AFTER operator;
