package star.home.comment.service;


import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import star.common.constants.CommonConstants;
import star.common.exception.client.YouAreNotAuthorException;
import star.common.exception.server.InternalServerException;
import star.common.service.BaseRetryRecoverService;
import star.home.board.model.entity.Board;
import star.home.comment.dto.CommentDTO;
import star.home.comment.dto.request.CommentRequest;
import star.home.comment.exception.InvalidIdCommentException;
import star.home.comment.model.entity.Comment;
import star.home.comment.repository.CommentRepository;
import star.member.dto.MemberInfoDTO;
import star.member.model.entity.Member;
import star.member.service.MemberService;

@Service
@RequiredArgsConstructor
public class CommentService extends BaseRetryRecoverService {

    private static final Integer TOP_LEVEL_COMMENT_DEPTH = 0;

    private final MemberService memberService;
    private final CommentRepository commentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Long createComment(MemberInfoDTO memberInfoDTO, CommentRequest request, Board board) {
        Member member = memberService.getMemberEntityById(memberInfoDTO.id());
        Comment parentComment = null;

        int depth = TOP_LEVEL_COMMENT_DEPTH;

        if (request.parentCommentId() != null) {
            parentComment = commentRepository.getCommentByIdAndBoardId(request.parentCommentId(),
                            board.getId())
                    .orElseThrow(InvalidIdCommentException::new);
            depth = parentComment.getDepth() + 1;
        }

        Comment comment = Comment.builder()
                .board(board)
                .parentComment(parentComment)
                .author(member)
                .depth(depth)
                .content(request.content())
                .build();

        increaseCommentCount(board);
        commentRepository.save(comment);

        return comment.getId();
    }

    //BFS 적으로 트리를 탐색
    @Transactional(readOnly = true)
    public List<CommentDTO> getComments(Long boardId) {

        List<CommentDTO> commentDTOList = new ArrayList<>();
        Integer maxDepth = commentRepository.findTopDepthByBoardId(boardId);

        if (maxDepth == null) {
            maxDepth = TOP_LEVEL_COMMENT_DEPTH - 1;
        }

        for (int i = TOP_LEVEL_COMMENT_DEPTH; i <= maxDepth; i++) {
            List<Comment> iLevelCommentList = commentRepository.getCommentsByBoardIdAndDepthOrderByCreatedAtAsc(
                    boardId, i);

            if (i == TOP_LEVEL_COMMENT_DEPTH) {
                iLevelCommentList.forEach(comment -> commentDTOList.add(CommentDTO.from(comment)));
                continue;
            }
            findParentAndAllocate(iLevelCommentList, commentDTOList);
        }
        return commentDTOList;
    }

    @Transactional
    public void updateComment(Long boardId, Long commentId, MemberInfoDTO memberInfoDTO,
            CommentRequest request) {
        Comment comment = commentRepository.getCommentByIdAndBoardId(commentId, boardId)
                .orElseThrow(InvalidIdCommentException::new);

        if (!comment.getAuthor().getId().equals(memberInfoDTO.id())) {
            throw new YouAreNotAuthorException();
        }

        comment.updateComment(request.content());
    }

    @Transactional
    public void hardDeleteComments(Long boardId) {
        commentRepository.deleteCommentsByBoardId(boardId);
    }

    @Transactional
    public void softDeleteComment(Long boardId, Long commentId, MemberInfoDTO memberInfoDTO) {
        Comment comment = commentRepository.getCommentByIdAndBoardId(commentId, boardId)
                .orElseThrow(InvalidIdCommentException::new);

        if (!comment.getAuthor().getId().equals(memberInfoDTO.id())) {
            throw new YouAreNotAuthorException();
        }

        comment.markAsDeprecated();
    }

    @Retryable(
            retryFor = OptimisticLockException.class,
            maxAttempts = CommonConstants.OPTIMISTIC_ATTEMPT_COUNT,
            backoff = @Backoff(delay = 100)
    )
    private void increaseCommentCount(Board board) {
        board.increaseCommentCount();
        entityManager.flush();
    }

    private void findParentAndAllocate(List<Comment> iLevelCommentList,
            List<CommentDTO> commentDTOList) {
        for (Comment notTopLevelComment : iLevelCommentList) {
            Long parentCommentId = notTopLevelComment.getParentComment().getId();

            CommentDTO foundParentCommentDTO = commentDTOList.stream()
                    .filter(commentDTO -> commentDTO.getId().equals(parentCommentId))
                    .findFirst()
                    .orElseThrow(() -> new InternalServerException(
                            "%d인 부모 댓글을 찾으려 했으나 못찾음".formatted(parentCommentId)));

            foundParentCommentDTO.addChildCommentDTO(CommentDTO.from(notTopLevelComment));

        }
    }
}
