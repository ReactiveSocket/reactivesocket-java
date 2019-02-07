package io.rsocket.frame.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.rsocket.ConnectionSetupPayload;
import io.rsocket.Payload;
import io.rsocket.frame.FrameHeaderFlyweight;
import io.rsocket.frame.FrameType;
import io.rsocket.frame.PayloadFrameFlyweight;
import io.rsocket.util.ByteBufPayload;

/**
 * Frame decoder that decodes a frame to a payload without copying. The caller is responsible for
 * for releasing the payload to free memory when they no long need it.
 */
public class ZeroCopyPayloadDecoder implements PayloadDecoder {
  @Override
  public Payload apply(ByteBuf byteBuf) {
    String s = ByteBufUtil.hexDump(byteBuf);
    try {
      FrameType frameType = FrameHeaderFlyweight.frameType(byteBuf);

      if (frameType == FrameType.SETUP) {
        return ConnectionSetupPayload.create(byteBuf);
      } else {
        ByteBuf data = PayloadFrameFlyweight.data(byteBuf);
        ByteBuf metadata = PayloadFrameFlyweight.metadata(byteBuf);
        return ByteBufPayload.create(data.retain(), metadata.retain());
      }
    } catch (Throwable t) {
      t.printStackTrace();
      System.out.println(s);
      System.exit(0);
      return null;
    }

    /*
    00000001286004232e667127bb590fb097cf657776761dcdfe84863f67da47e9de9ac72197424116aae3aadf9e4d8347d8f7923107f7eacb56f741c82327e9c4cbe23e92b5afc306aa24a2153e27082ba0bb1e707bed43100ea84a87539a23442e10431584a42f6eb78fdf140fb14ee71cf4a23ad644dcd3ebceb86e4d0617aa0d2cfee56ce1afea5062842580275b5fdc96cae1bbe9f3a129148dbe1dfc44c8e11aa6a7ec8dafacbbdbd0b68731c16bd16c21eb857c9eb1bb6c359415674b6d41d14e99b1dd56a40fc836d723dd534e83c44d6283745332c627e13bcfc2cd483bccec67232fff0b2ccb7388f0d37da27562d7c3635fef061767400e45729bdef57ca8c041e33074ea1a42004c1b8cb02eb3afeaf5f6d82162d4174c549f840bdb88632faf2578393194f67538bf581a22f31850f88831af632bdaf32c80aa6d96a7afc20b8067c4f9d17859776e4c40beafff18a848df45927ca1c9b024ef278a9fb60bdf965b5822b64bebc74a8b7d95a4bd9d1c1fc82b4fbacc29e36458a878079ddd402788a462528d6c79df797218563cc70811c09b154588a3edd2e948bb61db7b3a36774e0bd5ab67fec4bf1e70811733213f292a728389473b9f68d288ac481529e10cfd428b14ad3f4592d1cc6dd08b1a7842bb492b51057c4d88ac5d538174560f94b49dce6d20ef71671d2e80c2b92ead6d4a26ed8f4187a563cb53eb0c558fe9f77b2133e835e2d2e671978e82a6f60ed61a6a945e39fe0dedcf73d7cb80253a5eb9f311c78ef2587649436f4ab42bcc882faba7bfd57d451407a07ce1d5ac7b5f0cf1ef84047c92e3fbdb64128925ef6e87def450ae8a1643e9906b7dc1f672bd98e012df3039f2ee412909f4b03db39f45b83955f31986b6fd3b5e4f26b6ec2284dcf55ff5fbbfbfb31cd6b22753c6435dbd3ec5558132c6ede9babd7945ac6e697d28b9697f9b2450db2b643a1abc4c9ad5bfa4529d0e1f261df1da5ee035738a5d8c536466fa741e9190c58cf1cacc819838a6b20d85f901f026c66dbaf23cde3a12ce4b443ef15cc247ba48cc0812c6f2c834c5773f3d4042219727404f0f2640cab486e298ae9f1c2f7a7e6f0619f130895d9f41d343fbdb05d68d6e0308d8d046314811066a13300b1346b8762919d833de7f55fea919ad55500ba4ec7e100b32bbabbf9d378eab61532fd91d4d1977db72b828e8d700062b045459d7729f140d889a67472a035d564384844ff16697743e4017e2bf21511ebb4c939bbab202bf6ef59e2be557027272f1bb21c325cf3e0432120bccba17bea52a7621031466e7973415437cd50cc950e63e6e2d17aad36f7a943892901e763e19082260b88f8971b35b4d9cc8725d6e4137b4648427ae68255e076dfb511871de0f7100d2ece6c8be88a0326ba8d73b5c9883f83c0dccd362e61cb16c7a0cc5ff00f7

      ByteBuf data = PayloadFrameFlyweight.data(byteBuf);
      ByteBuf metadata = PayloadFrameFlyweight.metadata(byteBuf);
      return ByteBufPayload.create(data.retain(), metadata.retain());
    */
  }
}
