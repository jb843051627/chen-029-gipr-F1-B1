package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TGiprProductBookMapper;
import com.fc.v2.mapper.auto.TGiprTermBookMapper;
import com.fc.v2.model.auto.TGiprProductBook;
import com.fc.v2.model.auto.TGiprTermBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * 差额轧算 / 挂靠在册校验 / 软删口径单测。
 */
@ExtendWith(MockitoExtension.class)
class TGiprTermBookServiceImplTest {

    @Mock
    private TGiprTermBookMapper termMapper;
    @Mock
    private TGiprProductBookMapper productMapper;

    @InjectMocks
    private TGiprTermBookServiceImpl service;

    @org.junit.jupiter.api.BeforeEach
    void injectBaseMapper() {
        // baseMapper 在 MyBatis-Plus ServiceImpl 父类上，手动注入
        org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", termMapper);
    }

    private TGiprProductBook product(int status) {
        TGiprProductBook p = new TGiprProductBook();
        p.setId(0L);
        p.setProdNo("GP00");
        p.setProdName("在册产品");
        p.setStatus(status);
        p.setDelFlag(0);
        return p;
    }

    @Test
    void lack_is_expected_minus_hit_and_clamped() {
        when(productMapper.selectOne(any())).thenReturn(product(0));
        org.mockito.Mockito.doAnswer(inv -> 1).when(termMapper).insert(any(TGiprTermBook.class));

        TGiprTermBook r = new TGiprTermBook();
        r.setProdId(0);
        r.setCrtNum(10);
        r.setHitNum(4);
        service.insertTGiprTermBook(r);
        assertEquals(6, r.getLackNum());

        r.setCrtNum(3);
        r.setHitNum(8);
        service.insertTGiprTermBook(r);
        assertEquals(0, r.getLackNum());
    }

    @Test
    void rejected_when_product_moved_out_or_missing() {
        when(productMapper.selectOne(any())).thenReturn(product(1));
        TGiprTermBook r = new TGiprTermBook();
        r.setProdId(0);
        assertEquals(0, service.insertTGiprTermBook(r));

        when(productMapper.selectOne(any())).thenReturn(null);
        assertEquals(0, service.insertTGiprTermBook(r));
    }

    @Test
    void list_forces_online_and_keeps_caller_wrapper() {
        List<TGiprTermBook> data = new ArrayList<>();
        data.add(new TGiprTermBook());
        when(termMapper.selectList(any())).thenAnswer(inv -> {
            Wrapper<?> w = inv.getArgument(0);
            // 口径里必须同时带上 status 与 del_flag
            String seg = w.getSqlSegment();
            if (seg.contains("status") && seg.contains("del_flag")) {
                return data;
            }
            return new ArrayList<TGiprTermBook>();
        });
        assertEquals(1, service.selectTGiprTermBookList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TGiprTermBook>()
                        .eq("prod_no", "GP00")).size());
    }
}
