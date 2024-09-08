package com.ktg.mes.wm.service.impl;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.ktg.common.constant.UserConstants;
import com.ktg.common.exception.BussinessException;
import com.ktg.common.utils.DateUtils;
import com.ktg.common.utils.StringUtils;
import com.ktg.mes.md.domain.MdItem;
import com.ktg.mes.md.mapper.MdItemMapper;
import com.ktg.mes.wm.domain.*;
import com.ktg.mes.wm.mapper.WmMaterialStockMapper;
import com.ktg.mes.wm.service.IWmStorageAreaService;
import com.ktg.mes.wm.service.IWmStorageLocationService;
import com.ktg.mes.wm.service.IWmWarehouseService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ktg.mes.wm.mapper.WmTransactionMapper;
import com.ktg.mes.wm.service.IWmTransactionService;

/**
 * 库存事务Service业务层处理
 * 
 * @author yinjinlu
 * @date 2022-05-24
 */
@Service
public class WmTransactionServiceImpl implements IWmTransactionService 
{
    @Autowired
    private IWmWarehouseService wmWarehouseService;

    @Autowired
    private IWmStorageLocationService wmStorageLocationService;

    @Autowired
    private IWmStorageAreaService wmStorageAreaService;

    @Autowired
    private WmTransactionMapper wmTransactionMapper;

    @Autowired
    private WmMaterialStockMapper wmMaterialStockMapper;

    @Autowired
    private MdItemMapper mdItemMapper;

    @Override
    public synchronized WmTransaction processTransaction(WmTransaction wmTransaction) {
        WmMaterialStock stock = new WmMaterialStock();

        validate(wmTransaction); //先效验业务传递的库存事务参数是否完整
        initStock(wmTransaction,stock); //用库存事务来初始化一个MSD对象

        WmMaterialStock ms =wmMaterialStockMapper.loadMaterialStock(stock); //用MSD对象查询库存现有量，看是否已经存在相应的记录；如果存在则只对数量进行更新，不存在则需要新增MSD。

        checkFrozen(ms,stock); //冻结检测
        BigDecimal quantity = wmTransaction.getTransactionQuantity().multiply(new BigDecimal(wmTransaction.getTransactionFlag()));
        if(StringUtils.isNotNull(ms)){
            //MS已存在
            BigDecimal resultQuantity =ms.getQuantityOnhand().add(quantity);
            if(wmTransaction.isStorageCheckFlag() && resultQuantity.compareTo(new BigDecimal(0))<0){
                throw new BussinessException("库存数量不足！");
            }
            stock.setQuantityOnhand(resultQuantity);
            stock.setMaterialStockId(ms.getMaterialStockId());
            wmMaterialStockMapper.updateWmMaterialStock(stock);
        }else {
            //MS不存在

            //TODO:需要为库存增加一个效验逻辑：同一个库位上不能放不同批次和属性的物资

            stock.setQuantityOnhand(quantity);
            wmMaterialStockMapper.insertWmMaterialStock(stock);
        }
        wmTransaction.setMaterialStockId(stock.getMaterialStockId());
        wmTransaction.setTransactionQuantity(quantity);
        wmTransactionMapper.insertWmTransaction(wmTransaction);
        return wmTransaction;
    }


    private void validate(WmTransaction transaction){
        if(StringUtils.isNull(transaction.getTransactionType())){
            throw new BussinessException("库存事务不能为空");
        }

        if(StringUtils.isNull(transaction.getTransactionQuantity())){
            throw new BussinessException("事务数量不能为空");
        }

        if(StringUtils.isNull(transaction.getSourceDocCode())){
            throw new BussinessException("来源单据号不能为空");
        }

        if(StringUtils.isNull(transaction.getSourceDocLineId())){
            throw new BussinessException("来源单据行ID不能为空");
        }

        if(StringUtils.isNull(transaction.getTransactionDate())){
            transaction.setTransactionDate(new Date());
        }
    }


    public void initStock(WmTransaction transaction,WmMaterialStock stock){

        if(StringUtils.isNotNull(transaction.getMaterialStockId())){
            WmMaterialStock st = wmMaterialStockMapper.selectWmMaterialStockByMaterialStockId(transaction.getMaterialStockId());
            BeanUtils.copyProperties(st,stock);
        }else{
            MdItem item =mdItemMapper.selectMdItemById(transaction.getItemId());
            stock.setItemTypeId(item.getItemTypeId());
            stock.setItemId(transaction.getItemId());
            stock.setItemCode(transaction.getItemCode());
            stock.setItemName(transaction.getItemName());
            stock.setSpecification(transaction.getSpecification());
            stock.setUnitOfMeasure(transaction.getUnitOfMeasure());
            stock.setBatchCode(transaction.getBatchCode());
            stock.setWarehouseId(transaction.getWarehouseId());
            stock.setWarehouseCode(transaction.getWarehouseCode());
            stock.setWarehouseName(transaction.getWarehouseName());
            stock.setLocationId(transaction.getLocationId());
            stock.setLocationCode(transaction.getLocationCode());
            stock.setLocationName(transaction.getLocationName());
            if(StringUtils.isNotNull(transaction.getAreaId())){
                stock.setAreaId(transaction.getAreaId());
                stock.setAreaCode(transaction.getAreaCode());
                stock.setAreaName(transaction.getAreaName());
            }
            if(StringUtils.isNotNull(transaction.getVendorId())){
                stock.setVendorId(transaction.getVendorId());
                stock.setVendorCode(transaction.getVendorCode());
                stock.setVendorName(transaction.getVendorName());
                stock.setVendorNick(transaction.getVendorNick());
            }
            //使用库存事务日期初始化入库日期
            //一般在入库的时候才会涉及到materialStock的新增，出库的时候都是出的现有库存
            if(StringUtils.isNotNull(transaction.getRecptDate())){
                stock.setRecptDate(transaction.getRecptDate());
            }else{
                stock.setRecptDate(new Date());
            }

            //使用库存事务上的生产工单初始化库存记录上的生产工单，以支持某些情况下库存需要标记生产工单的情况
            if(StringUtils.isNotNull(transaction.getWorkorderId())){
                stock.setWorkorderId(transaction.getWorkorderId());
                stock.setWorkorderCode(transaction.getWorkorderCode());
            }
            stock.setExpireDate(transaction.getExpireDate());
        }
    }

    /**
     * 检查库存冻结情况
     * @param ms
     */
    private void checkFrozen(WmMaterialStock ms,WmMaterialStock org){
        //检查仓库冻结
        WmWarehouse warehouse = wmWarehouseService.selectWmWarehouseByWarehouseId(org.getWarehouseId());
        if(UserConstants.YES.equals(warehouse.getFrozenFlag())){
            throw new BussinessException("仓库"+warehouse.getWarehouseName()+"已被冻结！");
        }
        //检查库区冻结
        WmStorageLocation location = wmStorageLocationService.selectWmStorageLocationByLocationId(org.getLocationId());
        if(UserConstants.YES.equals(location.getFrozenFlag())){
            throw new BussinessException("库区"+location.getLocationName()+"已被冻结！");
        }
        //检查库位冻结
        WmStorageArea area = wmStorageAreaService.selectWmStorageAreaByAreaId(org.getAreaId());
        if(UserConstants.YES.equals(area.getFrozenFlag())){
            throw new BussinessException("库位"+area.getAreaName()+"已被冻结！");
        }
        //检查具体的MS冻结
        if(ms!=null && StringUtils.isNotNull(ms.getMaterialStockId())){
            if(UserConstants.YES.equals(ms.getFrozenFlag())){
                throw new BussinessException(new StringBuilder("存放于").append(ms.getWarehouseName()).append("/")
                        .append(ms.getLocationName()).append("/").append(ms.getAreaName()).append("下的")
                        .append(ms.getItemName()).append("已被冻结！").toString());
            }
        }
    }


    /**
     * 查询库存事务
     * 
     * @param transactionId 库存事务主键
     * @return 库存事务
     */
    @Override
    public WmTransaction selectWmTransactionByTransactionId(Long transactionId)
    {
        return wmTransactionMapper.selectWmTransactionByTransactionId(transactionId);
    }

    /**
     * 查询库存事务列表
     * 
     * @param wmTransaction 库存事务
     * @return 库存事务
     */
    @Override
    public List<WmTransaction> selectWmTransactionList(WmTransaction wmTransaction)
    {
        return wmTransactionMapper.selectWmTransactionList(wmTransaction);
    }

    /**
     * 新增库存事务
     * 
     * @param wmTransaction 库存事务
     * @return 结果
     */
    @Override
    public int insertWmTransaction(WmTransaction wmTransaction)
    {
        wmTransaction.setCreateTime(DateUtils.getNowDate());
        return wmTransactionMapper.insertWmTransaction(wmTransaction);
    }

    /**
     * 修改库存事务
     * 
     * @param wmTransaction 库存事务
     * @return 结果
     */
    @Override
    public int updateWmTransaction(WmTransaction wmTransaction)
    {
        wmTransaction.setUpdateTime(DateUtils.getNowDate());
        return wmTransactionMapper.updateWmTransaction(wmTransaction);
    }

    /**
     * 批量删除库存事务
     * 
     * @param transactionIds 需要删除的库存事务主键
     * @return 结果
     */
    @Override
    public int deleteWmTransactionByTransactionIds(Long[] transactionIds)
    {
        return wmTransactionMapper.deleteWmTransactionByTransactionIds(transactionIds);
    }

    /**
     * 删除库存事务信息
     * 
     * @param transactionId 库存事务主键
     * @return 结果
     */
    @Override
    public int deleteWmTransactionByTransactionId(Long transactionId)
    {
        return wmTransactionMapper.deleteWmTransactionByTransactionId(transactionId);
    }
}
