package com.example.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.canteen.entity.Store;
import com.example.canteen.mapper.StoreMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StoreService {
    private final StoreMapper storeMapper;

    public StoreService(StoreMapper storeMapper) {
        this.storeMapper = storeMapper;
    }

    public List<Store> getAllStores() {
        // 超管管理需要看到所有食堂(含停用),不在此处过滤 status
        return storeMapper.selectList(null);
    }

    /** 公开列表:H5/小程序登录页选择食堂用,只返回营业中的食堂 */
    public List<Store> getActiveStores() {
        return storeMapper.selectList(new LambdaQueryWrapper<Store>().eq(Store::getStatus, 1));
    }

    public Store getStoreById(Long id) {
        return storeMapper.selectById(id);
    }

    public Store createStore(Store store) {
        LocalDateTime now = LocalDateTime.now();
        if (store.getCreatedAt() == null) store.setCreatedAt(now);
        store.setUpdatedAt(now);
        storeMapper.insert(store);
        return store;
    }

    public Store updateStore(Store store) {
        // 显式更新 updatedAt,作为 ETag 源(branding 接口 ETag 基于 updatedAt 计算)
        store.setUpdatedAt(LocalDateTime.now());
        storeMapper.updateById(store);
        return store;
    }

    public void deleteStore(Long id) {
        storeMapper.deleteById(id);
    }
}
