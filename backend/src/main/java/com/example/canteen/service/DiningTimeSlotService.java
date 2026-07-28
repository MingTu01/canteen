package com.example.canteen.service;

import com.example.canteen.entity.DiningTimeSlot;
import com.example.canteen.exception.BusinessException;
import com.example.canteen.mapper.DiningTimeSlotMapper;
import com.example.canteen.security.SecurityContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DiningTimeSlotService {
    private final DiningTimeSlotMapper timeSlotMapper;

    public DiningTimeSlotService(DiningTimeSlotMapper timeSlotMapper) {
        this.timeSlotMapper = timeSlotMapper;
    }

    public List<DiningTimeSlot> getTimeSlotsByStore(Long storeId) {
        return timeSlotMapper.selectByStoreId(storeId);
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
