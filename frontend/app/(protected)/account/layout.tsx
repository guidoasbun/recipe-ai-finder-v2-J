import AccountNav from "./AccountNav";

export default function AccountLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-8 sm:flex-row">
      <AccountNav />
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  );
}
