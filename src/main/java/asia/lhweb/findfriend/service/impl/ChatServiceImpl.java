package asia.lhweb.findfriend.service.impl;

import asia.lhweb.findfriend.common.ErrorCode;
import asia.lhweb.findfriend.constants.ChatConstant;
import asia.lhweb.findfriend.constants.RedisConstants;
import asia.lhweb.findfriend.constants.UserConstants;
import asia.lhweb.findfriend.exception.BusinessException;
import asia.lhweb.findfriend.mapper.ChatMapper;
import asia.lhweb.findfriend.model.domain.Chat;
import asia.lhweb.findfriend.model.domain.Team;
import asia.lhweb.findfriend.model.domain.User;
import asia.lhweb.findfriend.model.request.ChatRequest;
import asia.lhweb.findfriend.model.vo.ChatMessageVO;
import asia.lhweb.findfriend.model.vo.WebSocketVO;
import asia.lhweb.findfriend.service.ChatService;
import asia.lhweb.findfriend.service.TeamService;
import asia.lhweb.findfriend.service.UserService;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 聊天服务实现
 *
 * @author 罗汉
 * @description 针对表【chat(聊天消息表)】的数据库操作Service实现
 * @createDate 2023-06-17 21:50:15
 * @date 2024/01/25
 */
@Service
public class ChatServiceImpl extends ServiceImpl<ChatMapper, Chat>
        implements ChatService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private UserService userService;

    @Resource
    private TeamService teamService;

    /**
     * 获取私人聊天
     *
     * @param chatRequest 聊天请求
     * @param chatType    聊天类型
     * @param loginUser   登录用户
     * @return {@link List}<{@link ChatMessageVO}>
     */
    @Override
    public List<ChatMessageVO> getPrivateChat(ChatRequest chatRequest, int chatType, User loginUser) {
        Long toId = chatRequest.getToId();
        if (toId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        List<ChatMessageVO> chatRecords = getCache(ChatConstant.CACHE_CHAT_PRIVATE, loginUser.getId() + String.valueOf(toId));
        if (chatRecords != null) {
            saveCache(ChatConstant.CACHE_CHAT_PRIVATE, loginUser.getId() + String.valueOf(toId), chatRecords);
            return chatRecords;
        }
        LambdaQueryWrapper<Chat> chatLambdaQueryWrapper = new LambdaQueryWrapper<>();
        chatLambdaQueryWrapper.
                and(privateChat -> privateChat.eq(Chat::getFromId, loginUser.getId()).eq(Chat::getToId, toId)
                        .or().
                        eq(Chat::getToId, loginUser.getId()).eq(Chat::getFromId, toId)
                ).eq(Chat::getChatType, chatType);
        // 两方共有聊天
        List<Chat> list = this.list(chatLambdaQueryWrapper);
        List<ChatMessageVO> chatMessageVOList = list.stream().map(chat -> {
            ChatMessageVO chatMessageVo = chatResult(loginUser.getId(),
                    toId, chat.getText(), chatType,
                    chat.getCreateTime());
            if (chat.getFromId().equals(loginUser.getId())) {
                chatMessageVo.setIsMy(true);
            }
            return chatMessageVo;
        }).collect(Collectors.toList());
        saveCache(ChatConstant.CACHE_CHAT_PRIVATE, loginUser.getId() + String.valueOf(toId), chatMessageVOList);
        return chatMessageVOList;
    }

    /**
     * 获取缓存
     *
     * @param redisKey redis键
     * @param id       id
     * @return {@link List}<{@link ChatMessageVO}>
     */
    @Override
    public List<ChatMessageVO> getCache(String redisKey, String id) {
        ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
        List<ChatMessageVO> chatRecords;
        if (redisKey.equals(ChatConstant.CACHE_CHAT_HALL)) {
            chatRecords = (List<ChatMessageVO>) valueOperations.get(redisKey);
        } else {
            chatRecords = (List<ChatMessageVO>) valueOperations.get(redisKey + id);
        }
        return chatRecords;
    }

    /**
     * 保存缓存
     *
     * @param redisKey       redis键
     * @param id             id
     * @param chatMessageVOS 聊天消息vos
     */
    @Override
    public void saveCache(String redisKey, String id, List<ChatMessageVO> chatMessageVOS) {
        try {
            ValueOperations<String, Object> valueOperations = redisTemplate.opsForValue();
            // 解决缓存雪崩
            int i = RandomUtil.randomInt(RedisConstants.MINIMUM_CACHE_RANDOM_TIME, RedisConstants.MAXIMUM_CACHE_RANDOM_TIME);
            if (redisKey.equals(ChatConstant.CACHE_CHAT_HALL)) {
                valueOperations.set(
                        redisKey,
                        chatMessageVOS,
                        RedisConstants.MINIMUM_CACHE_RANDOM_TIME + i / RedisConstants.CACHE_TIME_OFFSET,
                        TimeUnit.MINUTES);
            } else {
                valueOperations.set(
                        redisKey + id,
                        chatMessageVOS,
                        RedisConstants.MINIMUM_CACHE_RANDOM_TIME + i / RedisConstants.CACHE_TIME_OFFSET,
                        TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.error("redis set key error");
        }
    }

    /**
     * 聊天结果
     *
     * @param userId 用户id
     * @param text   文本
     * @return {@link ChatMessageVO}
     */
    private ChatMessageVO chatResult(Long userId, String text) {
        ChatMessageVO chatMessageVo = new ChatMessageVO();
        User fromUser = userService.getById(userId);
        WebSocketVO fromWebSocketVo = new WebSocketVO();
        BeanUtils.copyProperties(fromUser, fromWebSocketVo);
        chatMessageVo.setFromUser(fromWebSocketVo);
        chatMessageVo.setText(text);
        return chatMessageVo;
    }

    /**
     * 聊天结果
     *
     * @param userId     用户id
     * @param toId       到id
     * @param text       文本
     * @param chatType   聊天类型
     * @param createTime 创建时间
     * @return {@link ChatMessageVO}
     */
    @Override
    public ChatMessageVO chatResult(Long userId, Long toId, String text, Integer chatType, Date createTime) {
        ChatMessageVO chatMessageVo = new ChatMessageVO();
        User fromUser = userService.getById(userId);
        User toUser = userService.getById(toId);
        WebSocketVO fromWebSocketVo = new WebSocketVO();
        WebSocketVO toWebSocketVo = new WebSocketVO();
        BeanUtils.copyProperties(fromUser, fromWebSocketVo);
        BeanUtils.copyProperties(toUser, toWebSocketVo);
        chatMessageVo.setFromUser(fromWebSocketVo);
        chatMessageVo.setToUser(toWebSocketVo);
        chatMessageVo.setChatType(chatType);
        chatMessageVo.setText(text);
        chatMessageVo.setCreateTime(DateUtil.format(createTime, "yyyy-MM-dd HH:mm:ss"));
        return chatMessageVo;
    }

    /**
     * 删除密钥
     *
     * @param key 钥匙
     * @param id  id
     */
    @Override
    public void deleteKey(String key, String id) {
        if (key.equals(ChatConstant.CACHE_CHAT_HALL)) {
            redisTemplate.delete(key);
        } else {
            redisTemplate.delete(key + id);
        }
    }

    /**
     * 获取团队聊天
     *
     * @param chatRequest 聊天请求
     * @param chatType    聊天类型
     * @param loginUser   登录用户
     * @return {@link List}<{@link ChatMessageVO}>
     */
    @Override
    public List<ChatMessageVO> getTeamChat(ChatRequest chatRequest, int chatType, User loginUser) {
        Long teamId = chatRequest.getTeamId();
        if (teamId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求有误");
        }
        List<ChatMessageVO> chatRecords = getCache(ChatConstant.CACHE_CHAT_TEAM, String.valueOf(teamId));
        if (chatRecords != null) {
            List<ChatMessageVO> chatMessageVOS = checkIsMyMessage(loginUser, chatRecords);
            saveCache(ChatConstant.CACHE_CHAT_TEAM, String.valueOf(teamId), chatMessageVOS);
            return chatMessageVOS;
        }
        Team team = teamService.getById(teamId);
        LambdaQueryWrapper<Chat> chatLambdaQueryWrapper = new LambdaQueryWrapper<>();
        chatLambdaQueryWrapper.eq(Chat::getChatType, chatType).eq(Chat::getTeamId, teamId);
        List<ChatMessageVO> chatMessageVOS = returnMessage(loginUser, team.getUserId(), chatLambdaQueryWrapper);
        saveCache(ChatConstant.CACHE_CHAT_TEAM, String.valueOf(teamId), chatMessageVOS);
        return chatMessageVOS;
    }

    /**
     * 获得大厅聊天
     *
     * @param chatType  聊天类型
     * @param loginUser 登录用户
     * @return {@link List}<{@link ChatMessageVO}>
     */
    @Override
    public List<ChatMessageVO> getHallChat(int chatType, User loginUser) {
        List<ChatMessageVO> chatRecords = getCache(ChatConstant.CACHE_CHAT_HALL, String.valueOf(loginUser.getId()));
        if (chatRecords != null) {
            List<ChatMessageVO> chatMessageVOS = checkIsMyMessage(loginUser, chatRecords);
            saveCache(ChatConstant.CACHE_CHAT_HALL, String.valueOf(loginUser.getId()), chatMessageVOS);
            return chatMessageVOS;
        }
        LambdaQueryWrapper<Chat> chatLambdaQueryWrapper = new LambdaQueryWrapper<>();
        chatLambdaQueryWrapper.eq(Chat::getChatType, chatType);
        List<ChatMessageVO> chatMessageVOS = returnMessage(loginUser, null, chatLambdaQueryWrapper);
        saveCache(ChatConstant.CACHE_CHAT_HALL, String.valueOf(loginUser.getId()), chatMessageVOS);
        return chatMessageVOS;
    }

    /**
     * 支票是我信息
     *
     * @param loginUser   登录用户
     * @param chatRecords 聊天记录
     * @return {@link List}<{@link ChatMessageVO}>
     */
    private List<ChatMessageVO> checkIsMyMessage(User loginUser, List<ChatMessageVO> chatRecords) {
        return chatRecords.stream().peek(chat -> {
            if (chat.getFromUser().getId() != loginUser.getId() && chat.getIsMy()) {
                chat.setIsMy(false);
            }
            if (chat.getFromUser().getId() == loginUser.getId() && !chat.getIsMy()) {
                chat.setIsMy(true);
            }
        }).collect(Collectors.toList());
    }

    /**
     * 返回消息
     *
     * @param loginUser              登录用户
     * @param userId                 用户id
     * @param chatLambdaQueryWrapper 聊天lambda查询包装器
     * @return {@link List}<{@link ChatMessageVO}>
     */
    private List<ChatMessageVO> returnMessage(User loginUser,
                                              Long userId,
                                              LambdaQueryWrapper<Chat> chatLambdaQueryWrapper) {
        List<Chat> chatList = this.list(chatLambdaQueryWrapper);
        return chatList.stream().map(chat -> {
            ChatMessageVO chatMessageVo = chatResult(chat.getFromId(), chat.getText());
            boolean isCaptain = userId != null && userId.equals(chat.getFromId());
            if (userService.getById(chat.getFromId()).getRole() == UserConstants.ADMIN_ROLE || isCaptain) {
                chatMessageVo.setIsAdmin(true);
            }
            if (chat.getFromId().equals(loginUser.getId())) {
                chatMessageVo.setIsMy(true);
            }
            chatMessageVo.setCreateTime(DateUtil.format(chat.getCreateTime(), "yyyy年MM月dd日 HH:mm:ss"));
            return chatMessageVo;
        }).collect(Collectors.toList());
    }
}




