package asia.lhweb.findfriend.mapper;

import asia.lhweb.findfriend.model.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 用户映射器
 *
 * @author 罗汉
 * @description 针对表【user】的数据库操作Mapper
 * @createDate 2023-05-07 19:56:01
 * @Entity generator.domain.User
 * @date 2024/01/25
 */

public interface UserMapper extends BaseMapper<User> {

    /**
     * 收到随机用户
     *
     * @return {@link List}<{@link User}>
     */
    List<User> getRandomUser();
}




