import axios from 'axios';

export function getErrorMessage(err: unknown, defaultMessage = '작업을 처리하는 중 오류가 발생했습니다.'): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data;
    if (data && typeof data === 'object') {
      if ('message' in data && typeof data.message === 'string') {
        return data.message;
      }
    }
    if (err.message) {
      return err.message;
    }
  } else if (err instanceof Error) {
    return err.message;
  }
  return defaultMessage;
}
