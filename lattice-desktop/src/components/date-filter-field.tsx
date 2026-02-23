import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function todayDateValue() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

type DateFilterFieldProps = {
  label?: string;
  value: string;
  onChange: (value: string) => void;
};

export function DateFilterField({
  label = "日期",
  value,
  onChange,
}: DateFilterFieldProps) {
  const trimmedValue = value.trim();
  const isDateValid = trimmedValue.length === 0 || DATE_PATTERN.test(trimmedValue);

  return (
    <div className="grid gap-2">
      <div className="flex items-center justify-between gap-2">
        <Label>{label}</Label>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          className="h-7 px-2 text-xs"
          onClick={() => onChange(todayDateValue())}
        >
          今天
        </Button>
      </div>
      <Input
        type="date"
        value={value}
        aria-invalid={!isDateValid}
        onChange={(event) => onChange(event.currentTarget.value)}
      />
      {!isDateValid && (
        <div className="text-xs text-destructive">
          日期格式错误，请使用 YYYY-MM-DD。
        </div>
      )}
    </div>
  );
}
