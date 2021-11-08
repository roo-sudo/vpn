package com.dachui.vpn.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dachui.vpn.common.Result;
import com.dachui.vpn.config.AroundException;
import com.dachui.vpn.config.MessageConstants;
import com.dachui.vpn.enums.RedisConstantsKeyEnum;
import com.dachui.vpn.enums.OrderStatusEnum;
import com.dachui.vpn.enums.ReturnCodeStatusEnum;
import com.dachui.vpn.model.BaseEntity;
import com.dachui.vpn.model.po.*;
import com.dachui.vpn.model.vo.ComboResultVO;
import com.dachui.vpn.model.vo.PayRequestVO;
import com.dachui.vpn.model.vo.PlaceOrderRequestVO;
import com.dachui.vpn.repository.*;
import com.dachui.vpn.util.RedisUtil;
import com.dachui.vpn.util.StringUtil;
import io.netty.util.HashedWheelTimer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@SuppressWarnings("unchecked")
public class VpnService {

    @Resource
    private UserKnowMapper userKnowMapper;
    @Resource
    private VpnComboMapper vpnComboMapper;
    @Resource
    private OrderRecordsMapper orderRecordsMapper;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private UserMessageMapper userMessageMapper;
    @Resource
    private GrantOrderRecordMapper grantOrderRecordMapper;

    public Object getUserKnow() {
        Object o = redisUtil.get(RedisConstantsKeyEnum.USER_KNOW_KEY.getKey());
        List<String> collList = new ArrayList<>();
        if (o == null) {
            List<UserKnowPO> resultList = Optional.ofNullable(
                    userKnowMapper.selectList(
                            Wrappers.<UserKnowPO>lambdaQuery().orderByAsc(UserKnowPO::getId))).orElse(Collections.EMPTY_LIST);
            List<String> collect = resultList.stream().map(UserKnowPO::getText).collect(Collectors.toList());
            Integer i = 0;
            for (String t : collect) {
                i++;
                collList.add(i + "、".concat(t));
            }
            redisUtil.set(RedisConstantsKeyEnum.USER_KNOW_KEY.getKey(), collList, RedisConstantsKeyEnum.USER_KNOW_KEY.getCacheTime());
            return collList;
        }
        collList = ((List<String>) o);
        log.info(RedisConstantsKeyEnum.USER_KNOW_KEY.getDesc().concat("{}"), "---> 从缓存获取");
        return collList;
    }

    public Object getComboList() {
        List<ComboResultVO> collectList = new ArrayList<>();
        Object o = redisUtil.get(RedisConstantsKeyEnum.COM_BO_KEY.getKey());
        if (o == null) {
            List<VpnComboPO> list = Optional.ofNullable(
                    vpnComboMapper.selectList(
                            Wrappers.<VpnComboPO>lambdaQuery().eq(VpnComboPO::isDeleted, Boolean.FALSE).orderByDesc(VpnComboPO::getUpdateTime))).orElse(Collections.EMPTY_LIST);
            for (VpnComboPO po : list) {
                BigDecimal comboPrice = po.getComboPrice();
                ComboResultVO vo = new ComboResultVO();
                vo.setComboName(po.getComboName());
                vo.setComboPrice("￥".concat(comboPrice.toPlainString()).concat(".00 CNY"));
                vo.setId(po.getId());
                collectList.add(vo);
            }
            redisUtil.set(RedisConstantsKeyEnum.COM_BO_KEY.getKey(), collectList, RedisConstantsKeyEnum.COM_BO_KEY.getCacheTime());
            return collectList;
        }
        collectList = ((List<ComboResultVO>) o);
        log.info(RedisConstantsKeyEnum.COM_BO_KEY.getDesc().concat("{}"), "---> 从缓存获取");
        return collectList;
    }

    public VpnComboPO selectComboById(String id) {
        VpnComboPO po = vpnComboMapper.selectOne(
                Wrappers.<VpnComboPO>lambdaQuery().eq(VpnComboPO::getId, id).eq(VpnComboPO::isDeleted, Boolean.FALSE));
        return po;
    }

    public OrderRecordsPO placeTheOrder(PlaceOrderRequestVO requestVO) {
        Long comboId = requestVO.getComboId();
        if (comboId == null) {
            comboId = -1L;
        }
        VpnComboPO vpnComboPO = selectComboById(comboId.toString());
        if (vpnComboPO == null) {
            throw new AroundException(ReturnCodeStatusEnum.SYSTEM_ERROR, "套餐不存在！");
        }
        String orderNo = AlipayHandler.getOrderIdByTime();
        OrderRecordsPO orderRecordsPO = new OrderRecordsPO();
        Date now = new Date();
        orderRecordsPO.setComboId(vpnComboPO.getId());
        // TODO 模拟userId
        orderRecordsPO.setUserId(1L);
        orderRecordsPO.setComboName(vpnComboPO.getComboName());
        orderRecordsPO.setOrderId(orderNo);
        orderRecordsPO.setOrderPrice(requestVO.getPrice());
        orderRecordsPO.setPrice(requestVO.getPrice());
        orderRecordsPO.setDeleted(Boolean.FALSE);
        orderRecordsPO.setOrderStatus(OrderStatusEnum.PAY_NO.getCode());
        orderRecordsPO.setCreateTime(now);
        orderRecordsPO.setComboType(requestVO.getType());
        Calendar instance = Calendar.getInstance();
        instance.setTime(now);
        // TODO 订单失效时间 2 min
        instance.add(Calendar.MINUTE, 2);
        Date time = instance.getTime();
        orderRecordsPO.setFailureTime(time);
        orderRecordsMapper.insert(orderRecordsPO);
        log.info("下单成功！");
        // 将订单缓存在redis中
        redisUtil.set(
                RedisConstantsKeyEnum.ORDER_CACHE_KEY.getKey().concat(orderNo),
                orderRecordsPO,
                // 失效时间
                RedisConstantsKeyEnum.ORDER_CACHE_KEY.getCacheTime());
        HashedWheelTimer timer = new HashedWheelTimer();
        // 延时队列
        timer.newTimeout(
                timeout -> syncOrderStatus(orderNo), RedisConstantsKeyEnum.getDescTime(), TimeUnit.SECONDS);
        return orderRecordsPO;
    }

    private synchronized void syncOrderStatus(String orderNo) {
        log.info("--------> 开始处理订单：{}", orderNo);
        LambdaQueryWrapper<OrderRecordsPO> wrapper =
                Wrappers.<OrderRecordsPO>lambdaQuery().eq(OrderRecordsPO::getOrderId, orderNo).eq(OrderRecordsPO::isDeleted, Boolean.FALSE);
        OrderRecordsPO orderPo = orderRecordsMapper.selectOne(wrapper);
        if (orderPo != null) {
            Date date = new Date();
            LambdaUpdateWrapper<OrderRecordsPO> updateWrapper = Wrappers.lambdaUpdate();
            // 无效订单/已付款订单-关闭
            if (OrderStatusEnum.PAY_TIMEOUT.getCode().equals(orderPo.getOrderStatus())
                    || OrderStatusEnum.PAY_YES.getCode().equals(orderPo.getOrderStatus())) {
                orderRecordsMapper.update(null,
                        updateWrapper.eq(OrderRecordsPO::getId, orderPo.getId())
                                .set(OrderRecordsPO::isDeleted, Boolean.TRUE).set(OrderRecordsPO::getUpdateTime, date));
                log.info("发现一条无效/已付款订单-已关闭， id = {}\n", orderPo.getId());
                return;
            }
            // 从redis获取订单数据
            Object o = redisUtil.get(RedisConstantsKeyEnum.ORDER_CACHE_KEY.getKey().concat(orderNo));
            if (o == null || StringUtil.isEmpty(o.toString())) {
                orderRecordsMapper.update(null,
                        updateWrapper.eq(OrderRecordsPO::getOrderId, orderNo)
                                .set(OrderRecordsPO::isDeleted, Boolean.TRUE).set(OrderRecordsPO::getUpdateTime, date)
                                .set(OrderRecordsPO::getOrderStatus, OrderStatusEnum.PAY_TIMEOUT.getCode())
                );
                log.info("发现一条未支付已超时订单-已关闭， orderNo = {}\n", orderNo);
            }
        } else {
            log.info("该订单已经正常关闭，orderNo = {}\n", orderNo);
        }
    }

    public boolean closeOrder(String orderId) {
        int update = orderRecordsMapper.update(null,
                Wrappers.<OrderRecordsPO>lambdaUpdate().eq(OrderRecordsPO::getOrderId, orderId)
                        .set(OrderRecordsPO::isDeleted, Boolean.TRUE)
                        .set(OrderRecordsPO::getOrderStatus, OrderStatusEnum.PAY_OUT.getCode())
                        .set(OrderRecordsPO::getUpdateTime, new Date()));
        // 清空缓存订单数据
        if (update == 1)
            redisUtil.del(RedisConstantsKeyEnum.ORDER_CACHE_KEY.getKey().concat(orderId));
        return update == 1;
    }

    /**
     * TODO 1、检查订单状态 2、付款 3、对账 4、发货
     */

    // 检查订单状态-付款
    public Result<Object> pay(PayRequestVO payRequestVO) {
        System.err.println("orderId = " + payRequestVO.getOrderId());
        OrderRecordsPO orderRecordsPO = orderRecordsMapper.selectOne(
                Wrappers.<OrderRecordsPO>lambdaQuery().eq(OrderRecordsPO::getOrderId, payRequestVO.getOrderId()));
        if (orderRecordsPO == null || orderRecordsPO.isDeleted() || !OrderStatusEnum.PAY_NO.getCode().equals(orderRecordsPO.getOrderStatus())) {
            return Result.fail("该订单已失效，请重新下单");
        }
        // TODO 付款
        //return Result.success(AlipayHandler.TradeWapPayRequest(orderRecordsPO));
        orderRecordsMapper.update(null,
                Wrappers.<OrderRecordsPO>lambdaUpdate().eq(OrderRecordsPO::getOrderId, orderRecordsPO.getOrderId())
                        .set(OrderRecordsPO::getUpdateTime, new Date())
                        .set(OrderRecordsPO::getOrderStatus, OrderStatusEnum.PAY_YES.getCode())
                        .set(OrderRecordsPO::getPay, "支付宝")
                        .set(OrderRecordsPO::isDeleted, Boolean.TRUE));
        new Thread(() -> this.grantOrder(payRequestVO.getOrderId())).start();
        return Result.success("付款完成");
    }

    private void grantOrder(String orderId) {
        log.info(">>> 开始派发订单！");
        long start = System.currentTimeMillis();
        OrderRecordsPO orderRecordsPO;
        // 查询订单
        FIND_ORDER:
        {
            orderRecordsPO = orderRecordsMapper.selectOne(
                    Wrappers.<OrderRecordsPO>lambdaQuery().eq(OrderRecordsPO::isDeleted, Boolean.TRUE)
                            .eq(OrderRecordsPO::getOrderId, orderId)
            );
        }
        if (orderRecordsPO == null) return;
        GrantOrderRecordPO grantOrderRecordPO = new GrantOrderRecordPO();
        //grantOrderRecordPO.setVpnCommonId(orderRecordsPO.getComboId());
        grantOrderRecordPO.setCreateTime(new Date());
        grantOrderRecordPO.setSuccess(Boolean.FALSE);// TODO
        grantOrderRecordMapper.insert(grantOrderRecordPO);
        NEW_MESSAGE:{
            UserMessagePO userMessagePO = new UserMessagePO();
            userMessagePO.setDeleted(Boolean.FALSE);
            userMessagePO.setRead(Boolean.FALSE);
            userMessagePO.setContent(MessageConstants.orderMessage);
            userMessagePO.setMessageType(MessageConstants.MESSAGE);
            // 对于用户而言
            userMessagePO.setUserId(1L);
            userMessagePO.setCreateTime(new Date());
            userMessageMapper.insert(userMessagePO);
        }
    }

    /**
     * 回调完参数后调用
     */

    public Result<Map<String, Object>> getOrderStatus(String orderId) {
        // 过期 或 已付款 或 已关闭 = false else true
        OrderRecordsPO orderRecordsPO = orderRecordsMapper.selectOne(
                Wrappers.<OrderRecordsPO>lambdaQuery().eq(OrderRecordsPO::getOrderId, orderId));
        // 超时
        if (orderRecordsPO == null) {
            return Result.success(new HashMap<>());
        }
        boolean cs = new Date().after(orderRecordsPO.getFailureTime());
        HashMap<String, Object> map = new HashMap<>();
        map.put("status", orderRecordsPO.getOrderStatus());
        map.put("zcgb", false);
        if (!cs && orderRecordsPO.isDeleted() && !OrderStatusEnum.PAY_YES.getCode().equals(orderRecordsPO.getOrderStatus())) {
            map.put("zcgb", true);
        }
        return Result.success(map);
    }

    public Result<List<OrderRecordsPO>> getMyOrderList(String key, Integer pageSize) {
        // 模拟userId
        Long userId = 1L;
        if (pageSize >= 100) {
            pageSize = 100;
        }
        LambdaQueryWrapper<OrderRecordsPO> pages = Wrappers.<OrderRecordsPO>lambdaQuery()
                .eq(OrderRecordsPO::getUserId, userId)
                .and(StringUtils.isNotEmpty(key), i -> i.like(OrderRecordsPO::getOrderId, key)
                        .or().like(OrderRecordsPO::getComboName, key)
                )
                .last(" limit " + pageSize)
                .orderByDesc(OrderRecordsPO::getCreateTime);
        List<OrderRecordsPO> orderRecordsPOS = orderRecordsMapper.selectList(pages);
        if (!orderRecordsPOS.isEmpty()) {
            for (OrderRecordsPO orderPO : orderRecordsPOS) {
                orderPO.setOrderStatus(OrderStatusEnum.getMessageByCode(orderPO.getOrderStatus()));
            }
        }
        return Result.success(orderRecordsPOS);
    }

    public OrderRecordsPO getOrderById(String orderId) {
        OrderRecordsPO orderRecordsPO = orderRecordsMapper.selectOne(
                Wrappers.<OrderRecordsPO>lambdaQuery().eq(OrderRecordsPO::getOrderId, orderId).eq(BaseEntity::isDeleted, Boolean.FALSE).eq(OrderRecordsPO::getOrderStatus, OrderStatusEnum.PAY_NO));
        return orderRecordsPO == null ? new OrderRecordsPO() : orderRecordsPO;
    }

    public UserMessagePO getMessage() {
        Long userId = 1L;
        UserMessagePO userMessagePO = new UserMessagePO();
//        List<UserMessagePO> userMessagePOS = userMessageMapper.selectList(
//                Wrappers.<UserMessagePO>lambdaQuery().eq(BaseEntity::isDeleted, Boolean.FALSE)
//                        .eq(UserMessagePO::isRead, Boolean.FALSE).eq(UserMessagePO::getUserId, userId)
//        );
//        if (!CollectionUtils.isEmpty(userMessagePOS)) {
//            userMessagePO = userMessagePOS.get(0);
//        }
        return userMessagePO;
    }

    public void messageRead(Long messageId) {
        userMessageMapper.update(
                null, Wrappers.<UserMessagePO>lambdaUpdate()
                .eq(UserMessagePO::getMessageId, messageId)
                .set(UserMessagePO::getCreateTime, new Date())
                .set(UserMessagePO::isRead, Boolean.TRUE));
    }
}
