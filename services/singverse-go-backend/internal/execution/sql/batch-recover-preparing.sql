UPDATE audio_batch SET state='FAILED',preparation_error='准备任务被中断，请重新预览',updated_at=@{now} WHERE state='PREPARING'
