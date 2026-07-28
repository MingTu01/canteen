package com.example.canteen.dto;

import com.example.canteen.entity.Dish;
import com.example.canteen.entity.Menu;
import com.example.canteen.entity.MenuItem;
import lombok.Data;

import java.util.List;

/**
 * 菜单 + 菜品条目聚合视图
 */
@Data
public class MenuWithItemsDTO {
    private Menu menu;
    private List<ItemView> items;

    @Data
    public static class ItemView {
        private MenuItem item;
        private Dish dish;

        public ItemView(MenuItem item, Dish dish) {
            this.item = item;
            this.dish = dish;
        }
    }
}
