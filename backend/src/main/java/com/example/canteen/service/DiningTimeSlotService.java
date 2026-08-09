package com.example.canteen.service;

import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DiningTimeSlotMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DiningTimeSlotService {
    private final DiningTimeSlotMapper timeSlotMapper;

    public DiningTimeSlotService(DiningTimeSlotMapper timeSlotMapper) {
        this.timeSlotMapper = timeSlotMapper;
    }

    public List<DiningTimeSlot> getTimeSlotsByStore(Long storeId) {
        return timeSlotMapper.selectByStoreId(storeId);
    }

    /**
     * 按门店+餐次查询就餐时段配置。
     * @return 该餐次的时段配置;未配置则返回 null
     */
    public DiningTimeSlot getByStoreAndMealType(Long storeId, Integer mealType) {
        if (storeId == null || mealType == null) return null;
        List<DiningTimeSlot> slots = timeSlotMapper.selectByStoreId(storeId);
        if (slots == null || slots.isEmpty()) return null;
        return slots.stream()
                .filter(s -> mealType.equals(s.getMealType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断当前时间是否落在指定门店某餐次的就餐时段内。
     * 用于取餐核销时段校验:只在就餐时段内允许核销。
     * @param now 当前时间(已转换到门店时区)
     * @return true=在时段内可核销;false=未到/已过就餐时段
     */
    public boolean isWithinDiningTime(Long storeId, Integer mealType, LocalTime now) {
        DiningTimeSlot slot = getByStoreAndMealType(storeId, mealType);
        if (slot == null || slot.getStartTime() == null || slot.getEndTime() == null) {
            // 未配置时段:为安全起见拒绝核销(避免无配置即可任意核销)
            return false;
        }
        return !now.isBefore(slot.getStartTime()) && !now.isAfter(slot.getEndTime());
    }

    /**
     * 判断指定餐次的就餐时段是否已结束(用于定时任务标记未就餐)。
     * @param now 当前时间
     * @return true=当前时间已超过该餐次 endTime;false=尚未结束或未配置
     */
    public boolean isDiningTimePassed(Long storeId, Integer mealType, LocalTime now) {
        DiningTimeSlot slot = getByStoreAndMealType(storeId, mealType);
        if (slot == null || slot.getEndTime() == null) {
            return false; // 未配置时段不自动标记
        }
        return now.isAfter(slot.getEndTime());
    }

    /**
     * 查询当前时间所属的餐次(用于终端识别"现在是哪个餐次的取餐时间")。
     * @return 命中时段的 mealType;无命中返回 null(空档期)
     */
    public Integer getCurrentMealType(Long storeId, LocalTime now) {
        List<DiningTimeSlot> slots = timeSlotMapper.selectByStoreId(storeId);
        if (slots == null || slots.isEmpty()) return null;
        Map<Integer, DiningTimeSlot> map = slots.stream()
                .collect(Collectors.toMap(DiningTimeSlot::getMealType, s -> s, (a, b) -> a));
        for (Map.Entry<Integer, DiningTimeSlot> e : map.entrySet()) {
            DiningTimeSlot s = e.getValue();
            if (s.getStartTime() != null && s.getEndTime() != null
                    && !now.isBefore(s.getStartTime()) && !now.isAfter(s.getEndTime())) {
                return e.getKey();
            }
        }
        return null;
    }

    public DiningTimeSlot createTimeSlot(DiningTimeSlot timeSlot) {
        SecurityContext.checkStoreAccess(timeSlot.getStoreId());
        timeSlotMapper.insert(timeSlot);
        return timeSlot;
    }

    public DiningTimeSlot updateTimeSlot(DiningTimeSlot timeSlot) {
        DiningTimeSlot existing = timeSlotMapper.selectById(timeSlot.getId());
        if (existing == null) {
            throw new BusinessException("时段不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        // 用 existing.storeId 覆盖请求体,防止越权改门店
        timeSlot.setStoreId(existing.getStoreId());
        timeSlotMapper.updateById(timeSlot);
        return timeSlot;
    }

    public void deleteTimeSlot(Long id) {
        DiningTimeSlot existing = timeSlotMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException("时段不存在");
        }
        SecurityContext.checkStoreAccess(existing.getStoreId());
        timeSlotMapper.deleteById(id);
    }
}
